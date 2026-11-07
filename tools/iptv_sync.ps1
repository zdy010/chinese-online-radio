# 构建期从 iptv-org API 生成内置清单。不参与 Gradle 构建：更新清单 = 人工跑一次 + 提交 + 发版。
#
# 四条不能省（都是本项目踩过的坑）：
# 1. 一次性代理：github.com / raw.githubusercontent 直连实测超时，且不改任何全局配置。
# 2. 产出前校验：拉一半失败会留下残缺清单，而 App 照样能跑、只是"频道少得离谱"。
#    下限取基线的 80%，写死今日值会让上游正常波动把构建卡死，还会被误读成网络问题。
# 3. name 唯一性断言：direct 记录大量同名（master/manifest/live 这类无信息段），
#    消歧后仍有重名就不产出。
# 4. 失败即失败：任何一步网络错误都 throw 退出，绝不允许"拉不到就用上次生成的"却对外说是最新的。
#
# 输出形状与 radio_stations.json 一致：{"stations":[...]} 包装对象，
# 由 StationJson.parse 统一解析。另起一个 decodeFromString 就是第二套真相，
# 新字段（altNames/nsfw/quality/userAgent/referrer）会在那条路径上静默丢。

param(
    [string]$Proxy = "http://127.0.0.1:7897",
    [switch]$SkipAudit
)
$ErrorActionPreference = "Stop"

$assets = "app/src/main/assets/iptv_channels.json"
$audit  = "doc/iptv_nonofficial_hosts.md"
$work   = ".superpowers/tmp/iptv"
New-Item -ItemType Directory -Force -Path (Split-Path $assets) | Out-Null
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Fetch($url, $name) {
    $f = Join-Path $work $name
    Remove-Item $f -Force -ErrorAction SilentlyContinue
    curl.exe -sS --fail --max-time 180 -x $Proxy -o $f $url
    if ($LASTEXITCODE -ne 0) { throw "拉取失败（curl 退出码 $LASTEXITCODE）：$url`n代理 $Proxy 是否在监听？" }
    if (-not (Test-Path $f)) { throw "拉取后文件不存在：$name" }
    # 半截 JSON 会以"解析失败"的形式暴露，但先给出人话错误更好定位
    $head = (Get-Content $f -TotalCount 1 -Encoding UTF8)
    if ($head -notmatch '^\s*[\[{]') { throw "$name 不像 JSON（前几个字符：$($head.Substring(0,[Math]::Min(40,$head.Length)))）—— 可能拿到了错误页" }
    return $f
}

"=== 1 拉取 ==="
$chF  = Fetch "https://iptv-org.github.io/api/channels.json"   "channels.json"
$stF  = Fetch "https://iptv-org.github.io/api/streams.json"    "streams.json"
$ctyF = Fetch "https://iptv-org.github.io/api/countries.json"  "countries.json"
$catF = Fetch "https://iptv-org.github.io/api/categories.json" "categories.json"

# 语言播放列表是可选增强：主数据已够生成清单，语言列表只用来把"明确是中文/英语"的频道标出来。
# 拉不到就退回国家官方语言，不因为一个附加文件把整次同步卡死（但必须 warn，不能静默）。
function FetchText($url, $name) {
    $f = Join-Path $work $name
    Remove-Item $f -Force -ErrorAction SilentlyContinue
    curl.exe -sS --fail --max-time 180 -x $Proxy -o $f $url
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $f)) { return $null }
    return $f
}
$zhoIds = @{}; $engIds = @{}
$zhoF = FetchText "https://iptv-org.github.io/iptv/languages/zho.m3u" "lang-zho.m3u"
$engF = FetchText "https://iptv-org.github.io/iptv/languages/eng.m3u" "lang-eng.m3u"
if ($zhoF) { foreach ($m in [regex]::Matches((Get-Content $zhoF -Raw -Encoding UTF8), 'tvg-id="([^"@]+)')) { $zhoIds[$m.Groups[1].Value] = $true } }
if ($engF) { foreach ($m in [regex]::Matches((Get-Content $engF -Raw -Encoding UTF8), 'tvg-id="([^"@]+)')) { $engIds[$m.Groups[1].Value] = $true } }
if (-not $zhoF -or -not $engF) { Write-Warning "语言播放列表拉取失败，只能按国家官方语言判断语言（精度下降，但仍可用）" }
"语言列表：zho=$($zhoIds.Count) eng=$($engIds.Count)"

$ch  = Get-Content $chF  -Raw -Encoding UTF8 | ConvertFrom-Json
$st  = Get-Content $stF  -Raw -Encoding UTF8 | ConvertFrom-Json
$cty = Get-Content $ctyF -Raw -Encoding UTF8 | ConvertFrom-Json
$cat = Get-Content $catF -Raw -Encoding UTF8 | ConvertFrom-Json

"=== 2 输入校验（下限 = 基线 80%）==="
if (@($ch).Count  -lt 25000) { throw "channels 只有 $(@($ch).Count) 条，低于下限 25000" }
if (@($st).Count  -lt 14500) { throw "streams 只有 $(@($st).Count) 条，低于下限 14500" }
if (@($cat).Count -lt 25)    { throw "categories 只有 $(@($cat).Count) 条，低于下限 25" }

"=== 3 频道索引与流分组 ==="
$chIdx = @{}
foreach ($c in $ch) { if (-not $chIdx.ContainsKey([string]$c.id)) { $chIdx[[string]$c.id] = $c } }
$byChannel = @{}
$direct = @()
foreach ($s in $st) {
    $k = [string]$s.channel
    if ([string]::IsNullOrWhiteSpace($k) -or -not $chIdx.ContainsKey($k)) { $direct += $s; continue }
    if (-not $byChannel.ContainsKey($k)) { $byChannel[$k] = New-Object System.Collections.ArrayList }
    [void]$byChannel[$k].Add($s)
}

"=== 4 国家中文名：从 StationRegion.kt 读 ISO2_CN，不在这里复制第二份表 ==="
$iso = @{}
$srcText = Get-Content "app/src/main/java/com/radio/chinese/domain/model/StationRegion.kt" -Raw -Encoding UTF8
foreach ($m in [regex]::Matches($srcText, '"([A-Z]{2})"\s*to\s*"([^"]+)"')) { $iso[$m.Groups[1].Value] = $m.Groups[2].Value }
if ($iso["CN"] -ne "中国") { throw "从 StationRegion.kt 读 ISO2_CN 失败（CN≠中国）。不要在此硬编码，改那边或修正则" }
# 只验 CN 不够：将来若 StationRegion 里新增另一张"两个大写字母 to 中文串"的映射，
# 这个正则会静默多抓一批，而 CN 仍对 —— 多验几个常见码把这类污染拉红。
foreach ($probe in @(@("US","美国"), @("JP","日本"), @("GB","英国"))) {
    if ($iso[$probe[0]] -ne $probe[1]) { throw "ISO2_CN 读取异常：$($probe[0]) 应为 $($probe[1])，实为 '$($iso[$probe[0]])'" }
}
"ISO2_CN 读到 $($iso.Count) 个码"
$ctyName = @{}
foreach ($c in $cty) { $ctyName[[string]$c.code] = [string]$c.name }

function CNName($code) {
    if ([string]::IsNullOrWhiteSpace($code)) { return "" }
    if ($iso.ContainsKey($code))  { return $iso[$code] }
    if ($ctyName.ContainsKey($code)) { return $ctyName[$code] }   # 表没覆盖 → 回落英文名，不留空
    return ""
}

# 语言：优先用语言播放列表的明确标注，没有标注的用频道所属国家的官方语言。
# 不猜"名字里有没有中文"：alt_names 里的中文是译名，不代表播出语言。
$ctyLangs = @{}
foreach ($c in $cty) {
    $ctyLangs[[string]$c.code] = @($c.languages | ForEach-Object { [string]$_ } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
"=== 5b 中文频道归省：省名 + 主要地级市 + 台名特有关键词 ==="
# 顺序 = 匹配优先级：[ordered] 保证「全国」先判（CCTV/CGTN 不该落进北京），
# 长词优先（黑龙江/内蒙古/新疆生产建设兵团），避免「海南」把「海南州」抢走这类误判。
$provinceTable = [ordered]@{
    '全国'   = @('cctv', 'cgtn', '中国教育电视台', '中央电视', 'china travel', 'china weather', '中国气象')
    '北京'   = @('北京', 'brtv', 'beijing')
    '天津'   = @('天津', 'tianjin')
    '上海'   = @('上海', '东方卫视', 'dragon tv', 'shanghai', 'ics', 'smg', '风尚购物', 'fengshang')
    '重庆'   = @('重庆', 'chongqing')
    '河北'   = @('河北', '石家庄', '唐山', '秦皇岛', '邯郸', '邢台', '保定', '张家口', '承德', '沧州', '廊坊', '衡水', 'hebei', 'shijiazhuang', 'tangshan', 'qinhuangdao', 'handan', 'xingtai', 'baoding', 'zhangjiakou', 'chengde', 'cangzhou', 'langfang', 'hengshui')
    '山西'   = @('山西', '太原', '大同', '阳泉', '长治', '晋城', '朔州', '晋中', '运城', '忻州', '临汾', '吕梁', 'shanxi', 'taiyuan', 'datong', 'yangquan', 'changzhi', 'jincheng', 'shuozhou', 'jinzhong', 'yuncheng', 'xinzhou', 'linfen', 'lvliang')
    '内蒙古' = @('内蒙古', '呼和浩特', '包头', '乌海', '赤峰', '通辽', '鄂尔多斯', '呼伦贝尔', '巴彦淖尔', '乌兰察布', 'neimenggu', 'inner mongolia', 'hohhot', 'baotou', 'wuhai', 'chifeng', 'tongliao', 'ordos', 'hulunbuir', 'bayannur', 'ulanqab')
    '辽宁'   = @('辽宁', '沈阳', '大连', '鞍山', '抚顺', '本溪', '丹东', '锦州', '营口', '阜新', '辽阳', '盘锦', '铁岭', '朝阳', '葫芦岛', 'liaoning', 'shenyang', 'dalian', 'anshan', 'fushun', 'benxi', 'dandong', 'jinzhou', 'yingkou', 'fuxin', 'liaoyang', 'panjin', 'tieling', 'chaoyang', 'huludao')
    '吉林'   = @('吉林', '长春', '四平', '辽源', '通化', '白山', '松原', '白城', '延边', 'jilin', 'changchun', 'siping', 'liaoyuan', 'tonghua', 'baishan', 'songyuan', 'baicheng', 'yanbian')
    '黑龙江' = @('黑龙江', '哈尔滨', '齐齐哈尔', '鸡西', '鹤岗', '双鸭山', '大庆', '伊春', '佳木斯', '七台河', '牡丹江', '黑河', '绥化', 'heilongjiang', 'harbin', 'qiqihar', 'jixi', 'hegang', 'shuangyashan', 'daqing', 'yichun', 'jiamusi', 'qitaihe', 'mudanjiang', 'heihe', 'suihua')
    '江苏'   = @('江苏', '南京', '无锡', '徐州', '常州', '苏州', '南通', '连云港', '淮安', '盐城', '扬州', '镇江', '泰州', '宿迁', 'jiangsu', 'nanjing', 'wuxi', 'xuzhou', 'changzhou', 'suzhou', 'nantong', 'lianyungang', 'huaian', 'yancheng', 'yangzhou', 'zhenjiang', 'taizhou', 'suqian')
    '浙江'   = @('浙江', '杭州', '宁波', '温州', '嘉兴', '湖州', '绍兴', '金华', '衢州', '舟山', '台州', '丽水', 'zhejiang', 'hangzhou', 'ningbo', 'wenzhou', 'jiaxing', 'huzhou', 'shaoxing', 'jinhua', 'quzhou', 'zhoushan', 'taizhou', 'lishui')
    '安徽'   = @('安徽', '合肥', '芜湖', '蚌埠', '淮南', '马鞍山', '淮北', '铜陵', '安庆', '黄山', '滁州', '阜阳', '宿州', '六安', '亳州', '池州', '宣城', 'anhui', 'hefei', 'wuhu', 'bengbu', 'huainan', 'maanshan', 'huaibei', 'tongling', 'anqing', 'huangshan', 'chuzhou', 'fuyang', 'suzhou', 'luan', 'bozhou', 'chizhou', 'xuancheng')
    '福建'   = @('福建', '福州', '厦门', '莆田', '三明', '泉州', '漳州', '南平', '龙岩', '宁德', '海峡卫视', '东南卫视', 'fujian', 'fuzhou', 'xiamen', 'putian', 'sanming', 'quanzhou', 'zhangzhou', 'nanping', 'longyan', 'ningde')
    '江西'   = @('江西', '南昌', '景德镇', '萍乡', '九江', '新余', '鹰潭', '赣州', '吉安', '宜春', '抚州', '上饶', 'jiangxi', 'nanchang', 'jingdezhen', 'pingxiang', 'jiujiang', 'xinyu', 'yingtan', 'ganzhou', 'jian', 'yichun', 'fuzhou', 'shangrao')
    '山东'   = @('山东', '济南', '青岛', '淄博', '枣庄', '东营', '烟台', '潍坊', '济宁', '泰安', '威海', '日照', '临沂', '德州', '聊城', '滨州', '菏泽', 'shandong', 'jinan', 'qingdao', 'zibo', 'zaozhuang', 'dongying', 'yantai', 'weifang', 'jining', 'taian', 'weihai', 'rizhao', 'linyi', 'dezhou', 'liaocheng', 'binzhou', 'heze')
    '河南'   = @('河南', '郑州', '开封', '洛阳', '平顶山', '安阳', '鹤壁', '新乡', '焦作', '濮阳', '许昌', '漯河', '三门峡', '南阳', '商丘', '信阳', '周口', '驻马店', '济源', 'henan', 'zhengzhou', 'kaifeng', 'luoyang', 'pingdingshan', 'anyang', 'hebi', 'xinxiang', 'jiaozuo', 'puyang', 'xuchang', 'luohe', 'sanmenxia', 'nanyang', 'shangqiu', 'xinyang', 'zhoukou', 'zhumadian', 'jiyuan')
    '湖北'   = @('湖北', '武汉', '黄石', '十堰', '宜昌', '襄阳', '襄樊', '鄂州', '荆门', '孝感', '荆州', '黄冈', '咸宁', '随州', '恩施', 'hubei', 'wuhan', 'huangshi', 'shiyan', 'yichang', 'xiangyang', 'ezhou', 'jingmen', 'xiaogan', 'jingzhou', 'huanggang', 'xianning', 'suizhou', 'enshi')
    '湖南'   = @('湖南', '长沙', '株洲', '湘潭', '衡阳', '邵阳', '岳阳', '常德', '张家界', '益阳', '郴州', '永州', '怀化', '娄底', '湘西', 'hunan', 'changsha', 'zhuzhou', 'xiangtan', 'hengyang', 'shaoyang', 'yueyang', 'changde', 'zhangjiajie', 'yiyang', 'chenzhou', 'yongzhou', 'huaihua', 'loudi', 'xiangxi')
    '广东'   = @('广东', '广州', '韶关', '深圳', '珠海', '汕头', '佛山', '江门', '湛江', '茂名', '肇庆', '惠州', '梅州', '汕尾', '河源', '阳江', '清远', '东莞', '中山', '潮州', '揭阳', '云浮', '南方卫视', '珠江', '大湾区', 'guangdong', 'guangzhou', 'shaoguan', 'shenzhen', 'zhuhai', 'shantou', 'foshan', 'jiangmen', 'zhanjiang', 'maoming', 'zhaoqing', 'huizhou', 'meizhou', 'shanwei', 'heyuan', 'yangjiang', 'qingyuan', 'dongguan', 'zhongshan', 'chaozhou', 'jieyang', 'yunfu')
    '广西'   = @('广西', '南宁', '柳州', '桂林', '梧州', '北海', '防城港', '钦州', '贵港', '玉林', '百色', '贺州', '河池', '来宾', '崇左', 'guangxi', 'nanning', 'liuzhou', 'guilin', 'wuzhou', 'beihai', 'fangchenggang', 'qinzhou', 'guigang', 'yulin', 'baise', 'hezhou', 'hechi', 'laibin', 'chongzuo')
    '海南'   = @('海南', '海口', '三亚', '三沙', '儋州', 'hainan', 'haikou', 'sanya', 'sansha', 'danzhou')
    '四川'   = @('四川', '成都', '自贡', '攀枝花', '泸州', '德阳', '绵阳', '广元', '遂宁', '内江', '乐山', '南充', '眉山', '宜宾', '广安', '达州', '雅安', '巴中', '资阳', 'sichuan', 'chengdu', 'zigong', 'panzhihua', 'luzhou', 'deyang', 'mianyang', 'guangyuan', 'suining', 'neijiang', 'leshan', 'nanchong', 'meishan', 'yibin', 'guangan', 'dazhou', 'yaan', 'bazhong', 'ziyang')
    '贵州'   = @('贵州', '贵阳', '六盘水', '遵义', '安顺', '毕节', '铜仁', '黔东南', '黔南', '黔西南', 'guizhou', 'guiyang', 'liupanshui', 'zunyi', 'anshun', 'bijie', 'tongren', 'qiandongnan', 'qiannan', 'qianxinan')
    '云南'   = @('云南', '昆明', '曲靖', '玉溪', '保山', '昭通', '丽江', '普洱', '临沧', '楚雄', '红河', '文山', '西双版纳', '大理', '德宏', '怒江', '迪庆', 'yunnan', 'kunming', 'qujing', 'yuxi', 'baoshan', 'zhaotong', 'lijiang', 'puer', 'lincang', 'chuxiong', 'honghe', 'wenshan', 'xishuangbanna', 'dali', 'dehong', 'nujiang', 'diqing')
    '西藏'   = @('西藏', '拉萨', '日喀则', '昌都', '林芝', '山南', '那曲', '阿里', 'xizang', 'tibet', 'lhasa', 'shigatse', 'chamdo', 'nyingchi', 'shannan', 'nagqu', 'ali')
    '陕西'   = @('陕西', '西安', '铜川', '宝鸡', '咸阳', '渭南', '延安', '汉中', '榆林', '安康', '商洛', 'shaanxi', 'xian', 'tongchuan', 'baoji', 'xianyang', 'weinan', 'yanan', 'hanzhong', 'yulin', 'ankang', 'shangluo')
    '甘肃'   = @('甘肃', '兰州', '嘉峪关', '金昌', '白银', '天水', '武威', '张掖', '平凉', '酒泉', '庆阳', '定西', '陇南', '临夏', '甘南', 'gansu', 'lanzhou', 'jiayuguan', 'jinchang', 'baiyin', 'tianshui', 'wuwei', 'zhangye', 'pingliang', 'jiuquan', 'qingyang', 'dingxi', 'longnan', 'linxia', 'gannan', 'dunhuang', 'hezheng')
    '青海'   = @('青海', '西宁', '海东', '海北', '黄南', '海南州', '果洛', '玉树', '海西', 'qinghai', 'xining', 'haidong', 'haibei', 'huangnan', 'hainan zhou', 'golog', 'yushu', 'haixi')
    '宁夏'   = @('宁夏', '银川', '石嘴山', '吴忠', '固原', '中卫', 'ningxia', 'yinchuan', 'shizuishan', 'wuzhong', 'guyuan', 'zhongwei')
    '新疆'   = @('新疆', '乌鲁木齐', '克拉玛依', '吐鲁番', '哈密', '昌吉', '博尔塔拉', '巴音郭楞', '阿克苏', '克孜勒苏', '喀什', '和田', '伊犁', '塔城', '阿勒泰', '兵团', '石河子', 'xinjiang', 'urumqi', 'karamay', 'turpan', 'hami', 'changji', 'bortala', 'bayinguoleng', 'aksu', 'kizilsu', 'kashgar', 'hotan', 'ili', 'tacheng', 'altay', 'bingtuan', 'shihezi')
    '台湾'   = @('台湾', '台北', '新北', '桃园', '台中', '台南', '高雄', '基隆', '新竹', '嘉义', 'taiwan', 'taipei', 'new taipei', 'taoyuan', 'taichung', 'tainan', 'kaohsiung', 'keelung', 'hsinchu', 'chiayi')
    '香港'   = @('香港', 'hong kong', 'tvb', 'viutv', 'rthk')
    '澳门'   = @('澳门', 'macau', 'macao', 'tdm')
}

function RegionOf($nameZh, $nameEn) {
    $text = ("$nameZh $nameEn").ToLower()
    foreach ($prov in $provinceTable.Keys) {
        foreach ($kw in $provinceTable[$prov]) {
            if ($text.Contains($kw.ToLower())) { return $prov }
        }
    }
    return '其他'
}

function LangOf($id, $countryCode) {
    # 语言播放列表命中 = 明确语言；否则按国家官方语言推测，加 country: 前缀让 App 知道这是推测。
    # 前缀不是装饰：美国/印度这类多官方语言国家，只有推测集"全部是中英"才该保留，
    # 否则 eng,spa / eng,hin,tam 会把整批西语、印地语频道当成英语台放进来。
    if ($zhoIds.ContainsKey($id)) { return "zho" }
    if ($engIds.ContainsKey($id)) { return "eng" }
    $ls = $ctyLangs[$countryCode]
    if ($ls -and $ls.Count -gt 0) { return "country:" + ($ls -join ",") }
    return ""
}

"=== 5 direct 记录取名：分层 + junk 段名单 ==="
# 这些段本身无信息量：master/manifest/live/hls/stream 在 URL 末尾出现几百次，
# 直接当名字会让列表里成排同名（实测 master x174、manifest x50）
$junk = @('index.m3u8','playlist.m3u8','master','manifest','live','hls','stream','chunklist.m3u8','index','')
function DirectName($u) {
    $segs = @()
    try { $segs = @(([System.Uri]$u).AbsolutePath.Trim('/') -split '/') } catch { }
    for ($i = $segs.Count - 1; $i -ge 0; $i--) {
        $s = $segs[$i]
        if ([string]::IsNullOrWhiteSpace($s)) { continue }
        if ($junk -contains $s.ToLower()) { continue }
        if ($s -notmatch '[A-Za-z]') { continue }
        if ($s -match '^\d+$') { continue }
        return ($s -replace '\.(m3u8|mpd|index)$','')
    }
    try { return ([System.Uri]$u).Host } catch { return 'unknown' }
}
function Sha1Short($s) {
    $b = [System.Security.Cryptography.SHA1]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($s))
    return (($b | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 8)
}

"=== 6 生成记录 ==="
# iptv-org 有些频道的 name 是空/缺（实测发生过：消歧后剩下 " (117.169.120.140)" 这种名字）。
# 空名会让列表出现空白行，也会把后面的重名消歧带进死胡同，所以先兼几层兜底。
function ChannelName($c, $firstHost) {
    $n = [string]$c.name
    if (-not [string]::IsNullOrWhiteSpace($n)) { return $n.Trim() }
    $net = [string]$c.network
    if (-not [string]::IsNullOrWhiteSpace($net)) { return $net.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($firstHost)) { return $firstHost }
    return ([string]$c.id) -replace '\.[a-z]{2}$',''   # 最后用频道 id 去掉国家后缀
}

$rec = New-Object System.Collections.ArrayList
foreach ($k in $byChannel.Keys) {
    $c = $chIdx[$k]
    $srcs = @()
    foreach ($s in $byChannel[$k]) {
        $u = [string]$s.url
        if ([string]::IsNullOrWhiteSpace($u)) { continue }
        if ($u -notmatch '^https?://') { continue }
        # [string] 把上游的 null 归一成空串；不转的话 null 会变成字符串 "null" 并被当成有效 UA 发出去
        # [ordered] 不是风格问题：普通 @{} 是哈希表，ConvertTo-Json 按它的内部枚举顺序输出键，
        # 而 PowerShell 7 的字符串哈希跳进程随机化 —— 不固定键序的话，即使记录内容完全一致，
        # 两次生成的字节也不同（实测过：条数相同、SHA-256 不同）。
        $srcs += [ordered]@{ url = $u; label = ""; quality = [string]$s.quality
                   userAgent = [string]$s.user_agent; referrer = [string]$s.referrer }
    }
    if ($srcs.Count -eq 0) { continue }
    $desc = if ([string]::IsNullOrWhiteSpace([string]$c.network)) { [string]$c.name } else { [string]$c.network }
    if (-not [string]::IsNullOrWhiteSpace([string]$c.replaced_by)) { $desc += "（上游标记已被 $($c.replaced_by) 替换）" }
    # 省份：只对中国频道算，其余留空（不参与省份分组）
    $zhName = @($c.alt_names | ForEach-Object { [string]$_ } |
        Where-Object { $_ -match '[\u4e00-\u9fff]' } | Select-Object -First 1)
    $region = if ([string]$c.country -eq 'CN') { RegionOf ([string]$zhName) ([string]$c.name) } else { '' }
    [void]$rec.Add([ordered]@{
        id = "iptv:$k"; name = ChannelName $c ([System.Uri]$srcs[0].url).Host
        altNames = @(@($c.alt_names | ForEach-Object { [string]$_ }) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        category = "general"; streamUrl = $srcs[0].url; sources = $srcs
        website = [string]$c.website; description = $desc
        country = CNName ([string]$c.country); language = (LangOf $k ([string]$c.country)); region = $region
        nsfw = ($c.is_nsfw -eq $true); logoUrl = ""
        tags = @(@($c.categories | ForEach-Object { [string]$_ }) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    })
}
foreach ($d in $direct) {
    $u = [string]$d.url
    if ([string]::IsNullOrWhiteSpace($u) -or $u -notmatch '^https?://') { continue }
    $h = try { ([System.Uri]$u).Host } catch { 'unknown' }
    $dn = DirectName $u
    if ([string]::IsNullOrWhiteSpace($dn)) { $dn = $h }
    [void]$rec.Add([ordered]@{
        id = "iptv:direct:$($h):$(Sha1Short $u)"
        name = $dn; altNames = @(); category = "general"; streamUrl = $u
        sources = @([ordered]@{ url = $u; label = ""; quality = [string]$d.quality
                       userAgent = [string]$d.user_agent; referrer = [string]$d.referrer })
        website = ""; description = ""; country = ""; language = ""
        nsfw = $false; logoUrl = ""; tags = @()
    })
}

"=== 7 重名消歧：同名追加短 host，仍重则追加序号 ==="
# 排序不是美化，是可重复性的前提：PowerShell 7 走 .NET 的随机化字符串哈希，
# 所以 $byChannel.Keys 的枚举顺序跳进程不同。不排序的话每次重跑都会把整份 6.33 MB
# 频道部分重排，并把 #i 归属在两条同名片段间翻转 —— 一次真实的上游改动会被
# 几百万字符的重排 diff 淹没，review 与回归都做不了。
$rec = @($rec | Sort-Object { $_.id })
# 两级而不是抛错：实测同一 host 下会有多条流取名完全相同（包括两条都是空名的频道），
# 只追加 host 分不开。但绝不允许静默输出一份重名清单 —— 重名会让用户无法区分两行。
$dupes = @($rec | Group-Object { $_.name } | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
if ($dupes.Count -gt 0) {
    foreach ($r in $rec) {
        if ($dupes -contains $r.name) {
            $h = try { ([System.Uri]$r.streamUrl).Host } catch { 'x' }
            $r.name = "$($r.name) ($h)"
        }
    }
    "一级消歧：处理了 $($dupes.Count) 组重名"
}
$still = @($rec | Group-Object { $_.name } | Where-Object { $_.Count -gt 1 })
if ($still.Count -gt 0) {
    foreach ($g in $still) {
        $i = 0
        foreach ($r in @($rec | Where-Object { $_.name -eq $g.Name })) {
            $i++
            if ($i -gt 1) { $r.name = "$($g.Name) #$i" }   # 首条保留原名，后续加序号
        }
    }
    "二级消歧：仍有 $($still.Count) 组同名，已加序号"
}
$finalDup = @($rec | Group-Object { $_.name } | Where-Object { $_.Count -gt 1 })
if ($finalDup.Count -gt 0) {
    throw "两级消歧后仍有 $($finalDup.Count) 组重名（最大一组：$($finalDup[0].Name) x$($finalDup[0].Count)）—— 不产出残缺清单"
}
if (@($rec | Where-Object { [string]::IsNullOrWhiteSpace($_.name) }).Count -gt 0) {
    throw "存在空名条目，不产出（列表会出空白行）"
}

"=== 8 写盘前最后一道：条数下限 ==="
if (@($rec).Count -lt 10000) { throw "最终记录只有 $(@($rec).Count) 条，低于下限 10000" }

# 包装对象形状，与 radio_stations.json 一致（StationJson.parse 只认这个形状）
# 顶层也固定键序，否则整份文件的开头字节会逐进程变化。
# 注意：[ordered] 不能直接写在命令参数位置（PowerShell 会报“找不到接受自变量的位置参数”），
# 必须先赋值给变量再传给 ConvertTo-Json。
$wrapper = [ordered]@{ stations = $rec }
$json = ConvertTo-Json $wrapper -Depth 6 -Compress
Set-Content -Path $assets -Value $json -Encoding utf8
if ((Get-Item $assets).Length -lt 1MB) { throw "生成的 json 只有 $((Get-Item $assets).Length) 字节，明显不对" }

"=== 9 审计线索：非官方域名条目（V1 覆盖红线的追溯凭据）==="
if (-not $SkipAudit) {
    $nonofficial = New-Object System.Collections.ArrayList
    foreach ($k in $byChannel.Keys) {
        $c = $chIdx[$k]
        $web = [string]$c.website
        foreach ($s in $byChannel[$k]) {
            $u = [string]$s.url
            if ([string]::IsNullOrWhiteSpace($u)) { continue }
            try { $sh = ([System.Uri]$u).Host } catch { continue }
            if ([string]::IsNullOrWhiteSpace($web)) { [void]$nonofficial.Add("$k|（无 website）|$sh"); continue }
            try { $wh = ([System.Uri]$web).Host } catch { [void]$nonofficial.Add("$k|website 不可解析|$sh"); continue }
            # 比主域（最后两段）：www. 前缀与子域差异不算第三方
            $sBase = ($sh -replace '^www\.',''); $wBase = ($wh -replace '^www\.','')
            $tail  = ($sBase -split '\.')[-2..-1] -join '.'
            $wtail = ($wBase -split '\.')[-2..-1] -join '.'
            if ($tail -ne $wtail) { [void]$nonofficial.Add("$k|$wh|$sh") }
        }
    }
    $uniq = @($nonofficial | Sort-Object -Unique)
    $head = @(
        "# iptv 非官方域名条目清单",
        "",
        "由 tools/iptv_sync.ps1 生成，**勿手改**。",
        "",
        "这些条目的流主机名与该台官方站点**不同主域**（或该台根本没有 website）。",
        "按项目红线「电台音频源仅收录官方公开流」，它们不属于官方公开流 —— 常见形态是裸 IP 与境外机房转播。",
        "",
        "spec V1 决定全收（用户签字），本文件是审计线索，不是功能开关。",
        "",
        "生成时刻见本文件的最后一次提交信息，不写进正文（否则每次重跑都必改一行，制造无意义 diff）。",
        "",
        "| 频道 | 官方站点 | 流主机 |",
        "|---|---|---|"
    ) -join "`n"
    $body = ($uniq | ForEach-Object { $p = $_ -split '\|'; "| $($p[0]) | $($p[1]) | $($p[2]) |" }) -join "`n"
    Set-Content -Path $audit -Value ($head + "`n" + $body + "`n") -Encoding utf8
    "审计条目 = $($nonofficial.Count)（去重后 $($uniq.Count)）"
}

"=== 10 结果 ==="
"记录数        = $(@($rec).Count)"
"  频道记录    = $($byChannel.Keys.Count)"
"  direct 记录 = $($direct.Count)"
"json 未压缩   = $([math]::Round((Get-Item $assets).Length/1MB,2)) MB"
"成人条目      = $(@($rec | Where-Object { $_.nsfw }).Count)"
"分类 id       = $(@($cat | ForEach-Object { [string]$_.id } | Sort-Object -Unique).Count) 个"
"更新完成。提交前请跑：.\gradlew.bat :app:testDebugUnitTest --tests `"com.radio.chinese.IptvAssetsGateTest`""
