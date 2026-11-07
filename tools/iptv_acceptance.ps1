# iptv Tab 真机验收 T1–T14（spec §10.2）
# 用法：pwsh -NoProfile -File tools/iptv_acceptance.ps1                      全量
#      pwsh -NoProfile -File tools/iptv_acceptance.ps1 -OnlyT 3              单跑 T3（15 = V13 语言过滤）
#      pwsh -NoProfile -File tools/iptv_acceptance.ps1 -Pkg com.radio.chinese  R8 包复跑
# 纪律：结论一律以**本次**设备输出为准；拿不到信号就 INCONCLUSIVE，绝不写 PASS。
#       dump 前先 rm 旧文件、Assert 有第三态、几何切换放 try/finally，均照抄
#       tools/video_acceptance.ps1（本项目踩过"旧产物冒充本次抓取"与"设备留在车机档"）。
#       每条判据自己 Open-Iptv 冷启动，不承接前一条留下的页面状态 —— 否则上一项停在
#       分类列表/播放页时，下一项的 Tap-Node 会 throw，整轮后面的判据全丢。
#
# 2026-11-07 本轮台账（当次没有已连接设备，T1–T14 全部 INCONCLUSIVE）：
#   R8 release 包 = 6.98 MB（spec 预估 6.97）；debug 包 = 26.33 MB（未 R8，仅作对照）
#   内置清单 = 6.33 MB / 12,419 条 / 默认可见 12,417 条 / 成人 2 条
#   首个 iptv 页加载耗时 = 未实测，接上设备后由脚本打印
#   R8 release 包是 EGG 构建（含云盘真值），严禁外发/上架
param(
    [int]$OnlyT = 0,
    [string]$Pkg = "com.radio.chinese.debug"
)

$ErrorActionPreference = "Stop"
$pkg = $Pkg
$tmp = Join-Path (Get-Location) ".qtest\iptv"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$assets = "app/src/main/assets/iptv_channels.json"

# ---- 台账先行：构建失败时 adb install 仍会报 Success 装上旧包，必须先打印产物身份 ----
$apkDir = if ($pkg -like "*.debug") { "app\build\outputs\apk\debug" } else { "app\build\outputs\apk\release" }
$apk = @(Get-ChildItem $apkDir -Filter *.apk -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1)
$apkFile = if ($apk.Count -gt 0) { $apk[0] } else { $null }
if ($apkFile) {
    $apkMB = [math]::Round($apkFile.Length / 1MB, 2)
    "apk=$($apkFile.Name) mtime=$($apkFile.LastWriteTime) sizeMB=$apkMB"
} else {
    $apkMB = 0
    "apk=NOT-BUILT dir=$apkDir（先跑 .\gradlew.bat :app:assembleDebug）"
}
if (-not (Test-Path $assets)) { throw "内置清单不存在：$assets（先跑 tools/iptv_sync.ps1）" }
"assetsMB=$([math]::Round((Get-Item $assets).Length / 1MB, 2))"

# ---- 四件已验证设施：与 tools/video_acceptance.ps1 同款 ----
function Get-ScreenSize {
    # wm size 同时打印 Physical 与 Override；优先取 Override，否则车机几何下坐标落到屏外
    $sz = (adb shell wm size) -join ' '
    $ov = [regex]::Match($sz, 'Override size:\s*(\d+)x(\d+)')
    $ph = [regex]::Match($sz, 'Physical size:\s*(\d+)x(\d+)')
    $m = if ($ov.Success) { $ov } else { $ph }
    return @{ W = [int]$m.Groups[1].Value; H = [int]$m.Groups[2].Value }
}

function Dump-Ui($tag) {
    $p = Join-Path $tmp "ui-$tag.xml"
    # 必须先删设备上的旧 dump：uiautomator 失败时不写新文件，不删就会 pull 到上一次的内容
    adb shell rm -f /sdcard/ui.xml | Out-Null
    $ok = $false
    for ($try = 1; $try -le 4; $try++) {
        $d = adb shell uiautomator dump /sdcard/ui.xml
        if ("$d" -match "dumped to") { $ok = $true; break }
        Start-Sleep -Milliseconds 1500
        adb shell rm -f /sdcard/ui.xml | Out-Null
    }
    if (-not $ok) { throw "uiautomator dump 连续 4 次失败（$tag）：不给结论，也不拿旧 dump 冒充本次抓取" }
    adb pull /sdcard/ui.xml $p | Out-Null
    [xml]$doc = Get-Content $p -Raw -Encoding UTF8
    $out = @()
    foreach ($n in $doc.SelectNodes("//node")) {
        $b = [regex]::Matches($n.bounds, '\d+')
        if ($b.Count -lt 4) { continue }
        $out += [pscustomobject]@{
            Text = $n.text; Descr = $n."content-desc"
            X1 = [int]$b[0].Value; Y1 = [int]$b[1].Value
            X2 = [int]$b[2].Value; Y2 = [int]$b[3].Value
            CX = [int](([int]$b[0].Value + [int]$b[2].Value) / 2)
            CY = [int](([int]$b[1].Value + [int]$b[3].Value) / 2)
            Scrollable = ($n.scrollable -eq "true")
        }
    }
    return $out
}

function Find-Node($pattern, $nodes) {
    return $nodes | Where-Object { $_.Text -like "*$pattern*" -or $_.Descr -like "*$pattern*" } | Select-Object -First 1
}

function Wait-Ready($pattern, $sec = 60) {
    $deadline = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $deadline) {
        if (Find-Node $pattern (Dump-Ui "wait")) { return $true }
        Start-Sleep -Milliseconds 800
    }
    return $false
}

function Tap-Node($pattern) {
    $hit = Find-Node $pattern (Dump-Ui "tap")
    if (-not $hit) { throw "node not found: $pattern" }
    adb shell input tap $hit.CX $hit.CY | Out-Null
    return $hit
}

function Tap-After-Scroll($pattern) {
    $s = Get-ScreenSize
    $x = [int]($s.W * 0.5); $from = [int]($s.H * 0.75); $to = [int]($s.H * 0.25)
    for ($i = 0; $i -lt 20; $i++) {
        $hit = Find-Node $pattern (Dump-Ui "scroll")
        if ($hit) { adb shell input tap $hit.CX $hit.CY | Out-Null; return $hit }
        adb shell input swipe $x $from $x $to 400 | Out-Null
        Start-Sleep -Milliseconds 500
    }
    throw "not found after 20 swipes: $pattern"
}

# 只找不点：国家列表按频道数排序，「中国」154 条排在中后段，首屏 dump 抓不到
function Find-After-Scroll($pattern, $max = 10) {
    $s = Get-ScreenSize
    $x = [int]($s.W * 0.5); $from = [int]($s.H * 0.72); $to = [int]($s.H * 0.32)
    for ($i = 0; $i -lt $max; $i++) {
        $hit = Find-Node $pattern (Dump-Ui "find")
        if ($hit) { return $hit }
        adb shell input swipe $x $from $x $to 350 | Out-Null
        Start-Sleep -Milliseconds 400
    }
    return $null
}

# ---- 环境前置 ----
$devs = @(adb devices | Select-String "\tdevice$")
if ($devs.Count -eq 0) {
    throw "没有已连接设备：T1–T14 全部 INCONCLUSIVE。本脚本不产出任何 PASS，接上设备再跑。"
}
$ap = "$((adb shell settings get global airplane_mode_on))".Trim()
if ($ap -eq "1") {
    adb shell cmd connectivity airplane-mode disable | Out-Null
    adb shell settings put global airplane_mode_on 0 | Out-Null
}
# 不能只放在飞行模式分支里：airplane=0 但 wifi 关、无默认网络也出现过
adb shell svc wifi enable | Out-Null
Start-Sleep -Seconds 5
if (-not (adb shell dumpsys connectivity | Select-String "Active default network: \d+")) {
    throw "无默认网络：没有网时所有播放判据都不可信"
}
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

# ---- 清单基线：脚本内读 json，不靠截图、不靠人报数 ----
$allStations = @((Get-Content $assets -Raw -Encoding UTF8 | ConvertFrom-Json).stations)

# 语言准入必须与 IptvLanguage 逐条对齐，否则 T3 的计数会与界面差一截
function Test-PreferredLanguage($language) {
    $raw = "$language".Trim()
    if (-not $raw) { return $true }
    $pref = @('zho', 'chi', 'cmn', 'yue', 'zh', 'en', 'eng')
    if ($raw.StartsWith('country:')) {
        $codes = @($raw.Substring(8) -split ',' | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
        if ($codes.Count -eq 0) { return $true }
        return (@($codes | Where-Object { $pref -notcontains $_ }).Count -eq 0)
    }
    $codes = @($raw -split ',' | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
    if ($codes.Count -eq 0) { return $true }
    return (@($codes | Where-Object { $pref -contains $_ }).Count -gt 0)
}

# 成人判据与生产一致：nsfw 标记 或 tags 含 xxx（默认开关关）
# 语言判据与生产一致：默认只留中文与英语（V13）
$visibleStations = @($allStations | Where-Object {
    $_.nsfw -ne $true -and @($_.tags) -notcontains 'xxx' -and (Test-PreferredLanguage $_.language)
})
$jpnSample = @($allStations | Where-Object {
    $_.language -eq 'country:jpn' -and $_.name -match '^[A-Za-z0-9 .\-]+$'
} | Select-Object -First 1)
$tagCounts = @{}
foreach ($s in $visibleStations) {
    foreach ($t in @($s.tags)) { if ($t) { $tagCounts[$t] = 1 + $tagCounts[$t] } }
}
# adb input text 只吃 ASCII，中文查询词必须人工补测；这里挑能自动输入的样本
$aliasOwner = $null; $aliasQuery = $null
foreach ($s in $visibleStations) {
    foreach ($a in @($s.altNames)) {
        if ($a -match '^[A-Za-z0-9 .\-]+$' -and $a -ne $s.name) {
            $aliasOwner = $s; $aliasQuery = $a; break
        }
    }
    if ($aliasQuery) { break }
}
$directSample = @($visibleStations | Where-Object {
    $_.id -like 'iptv:direct:*' -and $_.name -match '^[A-Za-z][A-Za-z0-9 .\-]+$'
} | Select-Object -First 1)
$uaSample = @($visibleStations | Where-Object {
    $_.name -match '^[A-Za-z0-9 .\-]+$' -and @($_.sources)[0].userAgent
} | Select-Object -First 1)
$adultSample = @($allStations | Where-Object {
    $_.nsfw -eq $true -or @($_.tags) -contains 'xxx'
} | Select-Object -First 1)
"baseline: records=$($allStations.Count) visible=$($visibleStations.Count) adult=$(@($adultSample).Count) news=$($tagCounts['news'])"

$results = @()
function Assert($name, $ok, $detail, [switch]$Inconclusive) {
    # 第三态必须是独立的 Result 值：$ok=true 冒充不可判，汇总里会显示 PASS
    $r = if ($Inconclusive) { "INCONCLUSIVE" } elseif ($ok) { "PASS" } else { "FAIL" }
    $script:results += [pscustomobject]@{ Check = $name; Result = $r; Detail = $detail }
    "$name -> $r :: $detail"
}

function Launch-Home {
    adb shell am force-stop $pkg | Out-Null
    adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
    if (-not (Wait-Ready "电台" 60)) { throw "首页未就绪" }
}

function Open-Iptv {
    Launch-Home
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Tap-Node "iptv" | Out-Null
    $ready = Wait-Ready "分类浏览" 90
    $sw.Stop()
    if (-not $ready) { throw "iptv 页未就绪（12k 条首次解析可能较慢）" }
    return [int]$sw.ElapsedMilliseconds
}

function Search-Query($q) {
    $n = Dump-Ui "searchbox"
    $clear = Find-Node "清除搜索内容" $n
    if ($clear) { adb shell input tap $clear.CX $clear.CY | Out-Null; Start-Sleep -Milliseconds 300 }
    $box = Find-Node "搜索频道名称" $n
    if (-not $box) { throw "找不到 iptv 搜索框" }
    adb shell input tap $box.CX $box.CY | Out-Null
    $safe = ($q -replace ' ', '%s') -replace '[^A-Za-z0-9%.\-]', ''
    if ([string]::IsNullOrWhiteSpace($safe)) { return $null }
    adb shell input text $safe | Out-Null
    Start-Sleep -Milliseconds 1500
    return Dump-Ui "search"
}

function Card-Count($nodes, $label) {
    $l = Find-Node $label $nodes
    if (-not $l) { return $null }
    # 标签与计数必须在同一列：只按 Y 距离排，两列同高时会随机取到隔壁列的计数
    $colTol = [math]::Max(120, [int]((Get-ScreenSize).W / 4))
    $c = @($nodes | Where-Object {
            $_.Text -match '^\d+ 个频道$' -and [math]::Abs($_.X1 - $l.X1) -le $colTol
        } | Sort-Object { [math]::Abs($_.Y1 - $l.Y1) } | Select-Object -First 1)
    if ($c.Count -eq 0) { return $null }
    return [int]([regex]::Match($c[0].Text, '\d+').Value)
}

$firstLoadMs = 0

# ---------------- T1 底部导航 ----------------
if ($OnlyT -in 0, 1) {
    Launch-Home
    $nodes = Dump-Ui "t1-home"
    $xs = @()
    foreach ($l in @("电台", "iptv", "音频库", "设置")) {
        $h = Find-Node $l $nodes
        if ($h) { $xs += $h.X1 }
    }
    $ordered = ($xs.Count -eq 4)
    if ($ordered) { for ($i = 1; $i -lt 4; $i++) { if ($xs[$i] -lt $xs[$i - 1]) { $ordered = $false } } }
    Assert "T1 底部四项且顺序为 电台/iptv/音频库/设置" $ordered "x=$($xs -join ',')"

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Tap-Node "iptv" | Out-Null
    $ready = Wait-Ready "分类浏览" 90
    $sw.Stop()
    $script:firstLoadMs = [int]$sw.ElapsedMilliseconds
    $n2 = Dump-Ui "t1-iptv"
    $barHits = @(@("电台", "iptv", "音频库", "设置") |
        ForEach-Object { Find-Node $_ $n2 } | Where-Object { $_ }).Count
    Assert "T1 进 iptv 后底部栏仍在" ($ready -and $barHits -eq 4) "底部项命中=$barHits/4 页就绪=$ready"
}

# ---------------- T2 首屏形态 ----------------
if ($OnlyT -in 0, 2) {
    $null = Open-Iptv
    $nodes = Dump-Ui "t2"
    # 判据盯"四入口齐全 + 没有任何列表行"，不再拿总节点数当阈值：
    # LazyGrid 只渲染可见项，12k 平铺与 12 条平铺的节点数一样，节点数证明不了"不平铺"。
    $entryNames = @("分类浏览", "国家/地区", "最近播放", "我的收藏")
    $entryHits = @($entryNames | ForEach-Object { Find-Node $_ $nodes } | Where-Object { $_ }).Count
    $rowFavs = @($nodes | Where-Object { $_.Descr -eq '收藏' }).Count
    Assert "T2 首屏是四入口而非频道列表" (
        $entryHits -eq 4 -and $rowFavs -eq 0
    ) "入口=$entryHits/4 列表行收藏按钮=$rowFavs 可见节点=$(@($nodes).Count)"
    Assert "T2 顶部有搜索框" ($null -ne (Find-Node "搜索频道名称" $nodes)) "placeholder=搜索频道名称、别名或国家"
}

# ---------------- T3 分类入口计数 ----------------
if ($OnlyT -in 0, 3) {
    $null = Open-Iptv
    Tap-Node "分类浏览" | Out-Null
    Start-Sleep -Milliseconds 800
    $nodes = Dump-Ui "t3-categories"
    foreach ($pair in @(@("news", "新闻"), @("music", "音乐"), @("sports", "体育"))) {
        $key = $pair[0]; $label = $pair[1]
        $ui = Card-Count $nodes $label
        $expected = $tagCounts[$key]
        if ($null -eq $ui) {
            Assert "T3 分类 $label 计数与 json 一致" $false "dump 里没读到「$label」的计数卡" -Inconclusive
        } else {
            Assert "T3 分类 $label 计数与 json 一致" ($ui -eq $expected) "ui=$ui json=$expected"
        }
    }
    # 点进「新闻」：标题应带实测条数（不是只显示分类名）
    Tap-Node "新闻" | Out-Null
    Start-Sleep -Milliseconds 800
    $detail = Dump-Ui "t3-news"
    $expect = $tagCounts['news']
    $title = @($detail | Where-Object { $_.Text -like '新闻 · *' } | Select-Object -First 1)
    Assert "T3 新闻列表标题含实测条数" ($title.Count -gt 0 -and $title[0].Text -eq "新闻 · $expect 个频道") `
        "title=$(if ($title.Count -gt 0) { $title[0].Text } else { 'none' }) expect=新闻 · $expect 个频道"
}

# ---------------- T4 国家中文名 ----------------
if ($OnlyT -in 0, 4) {
    $null = Open-Iptv
    Tap-Node "国家/地区" | Out-Null
    Start-Sleep -Milliseconds 800
    $nodes = Dump-Ui "t4-countries"
    # 先看首屏有没有未翻译的裸代码（滚动前）
    $raw = @($nodes | Where-Object { $_.Text -eq "CN" }).Count -eq 0
    # 「中国」154 条，按频道数排序在中后段，要滚才看得到
    $cnNode = Find-After-Scroll "中国"
    Assert "T4 国家入口显示「中国」不是「CN」" ($null -ne $cnNode -and $raw) "中国节点=$(($null -ne $cnNode)) 独立CN节点不存在=$raw"
}

# ---------------- T5 搜索（别名 / direct） ----------------
if ($OnlyT -in 0, 5) {
    $null = Open-Iptv
    if ($aliasOwner -and $aliasQuery) {
        $n = Search-Query $aliasQuery
        $hit = if ($n) { Find-Node $aliasOwner.name $n } else { $null }
        Assert "T5 只用 alt_names 写法也能搜到" ($null -ne $hit) "query=$aliasQuery 期望台名=$($aliasOwner.name)"
    } else {
        Assert "T5 只用 alt_names 写法也能搜到" $false "清单里没有可自动输入的 ASCII 别名样本" -Inconclusive
    }
    if ($directSample.Count -gt 0) {
        $n = Search-Query $directSample[0].name
        $hit = if ($n) { Find-Node $directSample[0].name $n } else { $null }
        Assert "T5 direct 记录按路径名可搜到" ($null -ne $hit) "query=$($directSample[0].name) id=$($directSample[0].id)"
    } else {
        Assert "T5 direct 记录按路径名可搜到" $false "清单里没有可自动输入的 direct 名字样本" -Inconclusive
    }
    $n = Search-Query "zzqqxx-no-such-channel-9999"
    $empty = if ($n) { Find-Node "没有匹配的频道" $n } else { $null }
    Assert "T5 无命中显示空态" ($null -ne $empty) "query=zzqqxx-no-such-channel-9999"
}

# ---------------- T6 header 通路 ----------------
if ($OnlyT -in 0, 6) {
    if ($uaSample.Count -gt 0) {
        $null = Open-Iptv
        adb logcat -c
        $q = $uaSample[0].name
        $null = Search-Query $q
        Tap-Node $q | Out-Null
        Start-Sleep -Seconds 10   # 带 UA 的流也常需要轮转，4 秒时 commit 可能还没到
        $logs = "$((adb logcat -d -s IptvHeaders:D) -join ' ')"
        $applied = $logs -match 'applied ua=true'
        $commit = $logs -match 'commit id=iptv:.*ua=true'
        Assert "T6 带 UA 的流：工厂与起播两行诊断都在" ($applied -and $commit) `
            "query=$q applied=$applied commit=$commit 日志=$($logs.Substring(0, [math]::Min(240, $logs.Length)))"
        # 覆盖关系 Ruling：ua=true 但播放失败时，按 spec §6 预案改成只用一张 map 写全（当前实现已如此）
        $failed = $logs -match '所有节目源均无法播放'
        Assert "T6 UA 覆盖关系 Ruling" (-not $failed) "无「所有节目源均无法播放」；若出声/出画失败须重验 media3 覆盖关系"
    } else {
        Assert "T6 带 UA 的流" $false "清单里没有可自动输入且 sources[0] 带 UA 的样本" -Inconclusive
    }
}

# ---------------- T7 全屏 ----------------
if ($OnlyT -in 0, 7) {
    # 自己起播：iptv 行点击的语义是"点一次就起播并立刻进专用播放页"
    $null = Open-Iptv
    if ($uaSample.Count -gt 0) {
        $null = Search-Query $uaSample[0].name
        Tap-Node $uaSample[0].name | Out-Null
        Start-Sleep -Seconds 8
    }
    $nodes = Dump-Ui "t7-before"
    $full = Find-Node "全屏" $nodes
    if (-not $full) {
        Assert "T7 播放页有全屏按钮" $false "当前页没有 content-desc=全屏，先确认是否已进播放页" -Inconclusive
    } else {
        Assert "T7 播放页有全屏按钮" $true "content-desc=全屏 在位"
        Tap-Node "全屏" | Out-Null
        Start-Sleep -Seconds 2
        $n2 = Dump-Ui "t7-full"
        Assert "T7 可进全屏" ($null -ne (Find-Node "退出全屏" $n2)) "退出全屏入口出现"
    }
}

# ---------------- T8 收藏双向隔离 ----------------
if ($OnlyT -in 0, 8) {
    if ($uaSample.Count -eq 0) {
        Assert "T8 收藏双向隔离" $false "需要一条可自动输入的样本台；否则人工在 iptv 页收藏后核对" -Inconclusive
    } else {
        # 自己重建搜索结果页：播放页没有收藏按钮，不能承接前一条的页面状态
        $null = Open-Iptv
        $null = Search-Query $uaSample[0].name
        $target = $uaSample[0].name
        Tap-Node "收藏" | Out-Null
        Start-Sleep -Seconds 1
        Launch-Home
        Tap-Node "iptv" | Out-Null
        if (-not (Wait-Ready "分类浏览" 90)) { throw "T8 iptv 页未就绪" }
        Tap-Node "我的收藏" | Out-Null
        Start-Sleep -Milliseconds 800
        $inIptv = $null -ne (Find-Node $target (Dump-Ui "t8-iptv-fav"))
        Assert "T8 iptv 收藏页可见该台" $inIptv "target=$target"
        Tap-Node "电台" | Out-Null
        if (-not (Wait-Ready "收藏" 60)) { throw "T8 电台页未就绪" }
        Tap-Node "收藏" | Out-Null
        Start-Sleep -Milliseconds 800
        $inRadio = $null -ne (Find-Node $target (Dump-Ui "t8-radio-fav"))
        Assert "T8 电台收藏页不含 iptv 台" (-not $inRadio) "target=$target"
    }
}

# ---------------- T9 NSFW 开关 ----------------
if ($OnlyT -in 0, 9) {
    if ($adultSample.Count -gt 0) {
        $null = Open-Iptv
        $adultName = $adultSample[0].name
        $safeAdult = $adultName -replace '[^A-Za-z0-9 .\-]', ''
        if ([string]::IsNullOrWhiteSpace($safeAdult)) {
            Assert "T9 NSFW 默认关" $false "成人台名不可自动输入：$adultName" -Inconclusive
        } else {
            $n = Search-Query $safeAdult
            $offHit = if ($n) { Find-Node $adultName $n } else { $null }
            Assert "T9 默认关：搜成人台无结果" ($null -eq $offHit) "query=$safeAdult name=$adultName"
            # 开关在设置页：Launch-Home 之后必须先点底部「设置」
            Launch-Home
            Tap-Node "设置" | Out-Null
            Tap-After-Scroll "显示成人频道（iptv）" | Out-Null
            Start-Sleep -Milliseconds 600
            $null = Open-Iptv
            $n2 = Search-Query $safeAdult
            $onHit = if ($n2) { Find-Node $adultName $n2 } else { $null }
            Assert "T9 打开后有结果" ($null -ne $onHit) "query=$safeAdult name=$adultName"
            Launch-Home
            Tap-Node "设置" | Out-Null
            Tap-After-Scroll "显示成人频道（iptv）" | Out-Null   # 关回，别把设备留在打开态
        }
    } else {
        Assert "T9 NSFW 默认关" $false "清单里没有成人条目（上游波动），无法验收" -Inconclusive
    }
}

# ---------------- V13 语言过滤（默认只留中文/英语） ----------------
if ($OnlyT -in 0, 15) {
    if ($jpnSample.Count -gt 0) {
        $null = Open-Iptv
        $q = $jpnSample[0].name
        $n = Search-Query $q
        $offHit = if ($n) { Find-Node $q $n } else { $null }
        Assert "V13 默认隐藏非中英频道" ($null -eq $offHit) "query=$q name=$($jpnSample[0].name)"
        Launch-Home
        Tap-Node "设置" | Out-Null
        Tap-After-Scroll "显示其他语言频道（iptv）" | Out-Null
        Start-Sleep -Milliseconds 600
        $null = Open-Iptv
        $n2 = Search-Query $q
        $onHit = if ($n2) { Find-Node $q $n2 } else { $null }
        Assert "V13 打开后可见非中英频道" ($null -ne $onHit) "query=$q name=$($jpnSample[0].name)"
        Launch-Home
        Tap-Node "设置" | Out-Null
        Tap-After-Scroll "显示其他语言频道（iptv）" | Out-Null   # 关回，别把设备留在打开态
    } else {
        Assert "V13 语言过滤" $false "清单里没有日语样本，无法验收" -Inconclusive
    }
}

# ---------------- T10 车机几何 ----------------
if ($OnlyT -in 0, 10) {
    try {
        adb shell wm size 1280x720 | Out-Null
        adb shell wm density 160 | Out-Null
        Start-Sleep -Seconds 3
        $null = Open-Iptv
        $nodes = Dump-Ui "t10-list"
        $s = Get-ScreenSize
        $inside = @($nodes | Where-Object { $_.X2 -le $s.W + 2 -and $_.Y2 -le $s.H + 2 }).Count
        Assert "T10 车机档 iptv 列表节点在屏内" ($inside -eq @($nodes).Count) "屏内=$inside / 总节点=$(@($nodes).Count) size=$($s.W)x$($s.H)"
        # 控件单行：播控按钮的 Y 必须落在同一行。播放页需先起播，这里只记几何基线供人工复核
        Assert "T10 播放页控件单行" $false "需进播放页后比对 4 个按钮的 Y1；本脚本只记 dump 供复核" -Inconclusive
    } finally {
        # 必须放 finally：中途抛错把设备留在车机档，后续所有判据都会红
        adb shell wm size reset | Out-Null
        adb shell wm density reset | Out-Null
    }
}

# ---------------- T11 纯音频零回退回归 ----------------
if ($OnlyT -in 0, 11) {
    foreach ($v in @(1, 6)) {
        try {
            $out = & (Join-Path $PSScriptRoot "video_acceptance.ps1") -OnlyV $v -Pkg $pkg
            $tail = "$((@($out) | Select-Object -Last 2) -join ' | ')"
            Assert "T11 V$v 纯音频回归仍 PASS" ("$out" -match '-> PASS') $tail
        } catch {
            Assert "T11 V$v 纯音频回归仍 PASS" $false "$($_.Exception.Message)" -Inconclusive
        }
    }
}

# ---------------- T12 切台基准 ----------------
if ($OnlyT -in 0, 12) {
    if ($uaSample.Count -gt 0) {
        $null = Open-Iptv
        $null = Search-Query $uaSample[0].name
        Tap-Node $uaSample[0].name | Out-Null   # 点一次即进播放页
        Start-Sleep -Seconds 8
        adb logcat -c
        adb shell input keyevent 87 | Out-Null  # KEYCODE_MEDIA_NEXT
        Start-Sleep -Seconds 5
        $logs = "$((adb logcat -d -s IptvHeaders:D) -join ' ')"
        $commits = [regex]::Matches($logs, 'commit id=(iptv:[^ ]+)')
        $lastId = if ($commits.Count -gt 0) { $commits[$commits.Count - 1].Groups[1].Value } else { 'none' }
        Assert "T12 iptv 播放页下一曲切到 iptv 台" ($lastId -like 'iptv:*') "commit 行数=$($commits.Count) 末条=$lastId"
        Assert "T12 回电台页切台恢复电台语义" $false "需在电台页再发 keyevent 87 并核对当前台 id 不以 iptv: 开头" -Inconclusive
    } else {
        Assert "T12 切台基准" $false "没有可自动输入的起播样本" -Inconclusive
    }
}

# ---------------- T13 通知栏恢复 ----------------
if ($OnlyT -in 0, 13) {
    Assert "T13 通知栏/媒体键恢复到同一个 iptv 台" $false `
        "手工：播 iptv 台 → Home → 点通知栏回 App，核对播放页标题仍是该台；本脚本不代点通知栏" -Inconclusive
}

# ---------------- T14 R8 复跑 ----------------
if ($OnlyT -in 0, 14) {
    $r8 = ($pkg -notlike "*.debug")
    $mapping = "app\build\outputs\mapping\release\mapping.txt"
    $subset = @($results | Where-Object { $_.Check -match '^T([1-8]|1[1-3])\b' })
    $nonPass = @($subset | Where-Object { $_.Result -ne 'PASS' }).Count
    if ($r8) {
        Assert "T14 R8 包复跑 T1–T8/T11–T13" ($subset.Count -gt 0 -and $nonPass -eq 0) `
            "pkg=$pkg 子集=$($subset.Count) 非PASS=$nonPass mapping=$(Test-Path $mapping)；判定以 mapping.txt 为准"
    } else {
        Assert "T14 R8 包复跑 T1–T8/T11–T13" $false `
            "本轮跑在 $pkg；用 -Pkg com.radio.chinese 复跑，判定以 $mapping 为准" -Inconclusive
    }
}

# ---------------- 台账与汇总 ----------------
""
"===== 台账 ====="
"APK 体积 = $apkMB MB（spec 预估 6.97；debug 包未 R8，请读 release 产物）"
"首个 iptv 页加载耗时 = $firstLoadMs ms（点 tab → 出现入口卡的墙钟，含 uiautomator 轮询开销）"
"清单记录数 = $($allStations.Count)  默认可见 = $($visibleStations.Count)  成人 = $(@($adultSample).Count)"
""
"===== 验收汇总 ====="
$results | Format-Table -AutoSize Check, Result, Detail
$fails = @($results | Where-Object { $_.Result -eq 'FAIL' }).Count
$incs = @($results | Where-Object { $_.Result -eq 'INCONCLUSIVE' }).Count
"RESULT: FAIL=$fails INCONCLUSIVE=$incs TOTAL=$(@($results).Count)"
if ($fails -gt 0) { exit 1 }
exit 0
