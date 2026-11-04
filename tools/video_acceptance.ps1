# 视频播放验收脚本（V1–V6 可重跑；V7 见文末说明）
# 用法：.\.qtest\video_acceptance.ps1            全量跑
#      .\ .qtest\video_acceptance.ps1 -OnlyV 6   只跑某一项
# 依赖：已连接的模拟器/设备、已装好本次 debug 包、`.superpowers\tmp\sdd-ui.ps1` 的节点解析写法。
# 本脚本不是产品代码；结论一律以真机输出为准，禁止用历史产物冒充本轮结果。

param([int]$OnlyV = 0)

$ErrorActionPreference = "Stop"
$pkg = "com.radio.chinese.debug"
$tmp = Join-Path (Get-Location) ".qtest\video"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Get-ScreenSize {
    # wm size 会同时打印 Physical 与 Override；必须优先取 Override，
    # 否则在车机几何下拿到物理分辨率，滑动力坐标落到屏外，列表根本滑不动（本轮踩过）
    $sz = (adb shell wm size) -join ' '
    $ov = [regex]::Match($sz, 'Override size:\s*(\d+)x(\d+)')
    $ph = [regex]::Match($sz, 'Physical size:\s*(\d+)x(\d+)')
    $m = if ($ov.Success) { $ov } else { $ph }
    return @{ W = [int]$m.Groups[1].Value; H = [int]$m.Groups[2].Value }
}

function Dump-Ui($tag) {
    $p = Join-Path $tmp "ui-$tag.xml"
    # 必须先删设备上的旧 dump：uiautomator 失败（null root node）时不会写新文件，
    # 不删就会 pull 到上一次的内容 —— “两次抓出来一模一样”其实是旧产物冒充本次抓取。
    # 对 F6b/F7 这类“断言节点不存在”的负向判据，这会直接造出假 PASS（缺陷被洗白）。
    adb shell rm -f /sdcard/ui.xml | Out-Null
    # 重试再招：窗口动画期会出现 "null root node"（stdout 为空、文件不写），这是环内抖动不是失败。
    # 但重试必须建立在"先 rm"之上：不删就 pull 到旧文件，那才是本轮真实发生过的假结论。
    $ok = $false
    for ($try = 1; $try -le 4; $try++) {
        $d = adb shell uiautomator dump /sdcard/ui.xml
        if ("$d" -match "dumped to") { $ok = $true; break }
        Start-Sleep -Milliseconds 1500
        adb shell rm -f /sdcard/ui.xml | Out-Null
    }
    if (-not $ok) { throw "uiautomator dump 连续 4 次失败（$tag）：宁可不给结论，也不能拿旧 dump 冒充本次抓取" }
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
    # 冷启动就绪轮询：不轮询就会在第一帧空窗里点空，把环境问题误读成 App 缺陷
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

function Enter-Station($name) {
    adb shell am force-stop $pkg | Out-Null
    adb logcat -c
    adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
    if (-not (Wait-Ready "电视伴音")) { throw "home not ready" }
    for ($i = 0; $i -lt 4; $i++) {
        $nodes = Dump-Ui "nav"
        if (Find-Node "CCTV-5" $nodes) { break }
        $chip = Find-Node "电视伴音" $nodes
        if ($chip) { adb shell input tap $chip.CX $chip.CY | Out-Null }
        Start-Sleep -Seconds 3
    }
    Tap-After-Scroll $name | Out-Null
    Start-Sleep -Seconds 15
    adb shell input keyevent 4 | Out-Null      # 回首页，露出迷你条
    Start-Sleep -Seconds 2
    Tap-Node $name | Out-Null                  # 点迷你条进播放页
    Start-Sleep -Seconds 12
}

function Shot-Diff($tag, $gapMs) {
    # 两帧差分：单阈值判黑会把深色画面误判成黑屏（本项目假失败过）。
    # 但差分也会把「静止的直播画面」判成无变化，所以结论必须再配一张人工可判读的截图。
    $paths = @()
    for ($i = 1; $i -le 2; $i++) {
        $p = Join-Path $tmp "$tag-$i.png"
        adb shell screencap -p /sdcard/s.png | Out-Null
        adb pull /sdcard/s.png $p | Out-Null    # 必须走 pull：PS 的 `>` 会按文本重写PNG字节
        $paths += $p
        if ($i -lt 2) { Start-Sleep -Milliseconds $gapMs }
    }
    $a = [System.Drawing.Bitmap]::FromFile($paths[0])
    $b = [System.Drawing.Bitmap]::FromFile($paths[1])
    $w = [Math]::Min($a.Width, $b.Width); $h = [Math]::Min($a.Height, $b.Height)
    $changed = 0; $total = 0
    for ($y = 0; $y -lt $h; $y += 4) {
        for ($x = 0; $x -lt $w; $x += 4) {
            $ca = $a.GetPixel($x, $y); $cb = $b.GetPixel($x, $y)
            $total++
            if (([Math]::Abs($ca.R - $cb.R) + [Math]::Abs($ca.G - $cb.G) + [Math]::Abs($ca.B - $cb.B)) -gt 18) { $changed++ }
        }
    }
    $a.Dispose(); $b.Dispose()
    return @{ changed = $changed; total = $total }
}

Add-Type -AssemblyName System.Drawing

# 环境前置：先确认有设备。不守这一步，下面的 `.Trim()` 会在 null 上崩，
# 报的是“不能对 Null 值表达式调用方法”，看的人以为是脚本 bug，而不是没接设备。
$devs = @(adb devices | Select-String "\tdevice$")
if ($devs.Count -eq 0) { throw "没有已连接设备：先起模拟器（emulator -avd Pixel_9a）或插真机再跑" }

# 飞行模式与断网都会让取样变成“无网络”的假失败（本轮实测踩过）。
# 注意：`svc wifi enable` 不能只放在飞行模式分支里 —— 本轮遇到过
# airplane_mode_on=0 但 Active default network=none 的情况， wifi 本身也是关的。
$ap = "$((adb shell settings get global airplane_mode_on))".Trim()
if ($ap -eq "1") {
    adb shell cmd connectivity airplane-mode disable | Out-Null
    adb shell settings put global airplane_mode_on 0 | Out-Null
}
adb shell svc wifi enable | Out-Null
Start-Sleep -Seconds 5
if (-not (adb shell dumpsys connectivity | Select-String "Active default network: \d+")) {
    throw "无默认网络（Active default network: none）：等几秒再跑；没有网时所有视频/直播判据都不可信"
}
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

$results = @()

function Assert($name, $ok, $detail, [switch]$Inconclusive) {
    # 第三态必须是独立的 Result 值。上一版用 `Assert $true` 冒充“不可判”，
    # 汇总表里显示 PASS —— 只看汇总或只 grep FAIL 的人会把未验证当成已守住（本轮真实发生过）。
    $r = if ($Inconclusive) { "INCONCLUSIVE" } elseif ($ok) { "PASS" } else { "FAIL" }
    $script:results += [pscustomobject]@{ Check = $name; Result = $r; Detail = $detail }
    "$name -> $r :: $detail"
}

if ($OnlyV -in 0, 1) {
    # V1 手机档零回退：纯音频台的播放页不得出现 VideoStage，且闸门不得被开过
    Enter-Station "CCTV-5体育伴音"
    $nodes = Dump-Ui "v1"
    $gateLogs = adb logcat -d -s VideoGate:D
    $probe = adb logcat -d -s VideoProbe:D | Select-Object -Last 1
    # 不拿「节点里没有‘画面’字样」当证据：遮罩文案已取消，那会变成永远为真的空断言。
    # 真正可证伪的是：闸门从未开过 + 播放器从未报出视频组 + 视频轨始终处于禁用。
    $gateNeverOpened = -not ($gateLogs | Select-String "decode=true")
    $noVideoGroup = -not ("$probe" -match "type=2")
    $videoTrackDisabled = "$probe" -match "disabled=\[.*2.*\]"
    Assert "V1 纯音频台闸门未开过" ($gateNeverOpened -and $noVideoGroup) "gate=$gateNeverOpened noVideoGroup=$noVideoGroup probe=$($probe -replace '\s+',' ')"
    Assert "V1 视频轨保持禁用" $videoTrackDisabled "$($probe -replace '\s+',' ')"
    "V1 附：画面区应该被封面替掉，人工核对 $tmp\ui-v1.xml 与截图"
}

if ($OnlyV -in 0, 2) {
    # V2 有画面的台：闸门必须开、画面必须真的在渲染
    Enter-Station "人间卫视"
    $gate = adb logcat -d -s VideoGate:D | Select-String "decode=true"
    $probe = adb logcat -d -s VideoProbe:D | Select-Object -Last 1
    $diff = Shot-Diff "v2" 2000
    Assert "V2 挂载后闸门开启" ([bool]$gate) "gate lines=$(($gate | Measure-Object).Count)"
    Assert "V2 播放器上报视频轨" ("$probe" -match "type=2") "$($probe -replace '\s+',' ')"
    "V2 两帧差分 changed=$($diff.changed)/$($diff.total)（0 不等于无画面：直播静止帧会相同，须人工看 $tmp\v2-1.png）"
}

if ($OnlyV -in 0, 3) {
    # V3 退出播放页后闸门必须收回，且音频继续
    adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 4
    $last = adb logcat -d -s VideoGate:D | Select-Object -Last 1
    Assert "V3 退出后收闸" ("$last" -match "decode=false") "$($last -replace '\s+',' ')"
    $still = adb shell dumpsys media_session | Select-String "state.active=true|players=[^]]*state=playing"
    "V3 音频是否仍在播（人工核对 media_session）：$([bool]$still)"
}

if ($OnlyV -in 0, 4) {
    # V4 方控媒体键：87 是 NEXT（88 是 PREVIOUS，别记反）
    Enter-Station "人间卫视"
    adb logcat -c
    adb shell input keyevent 87 | Out-Null
    Start-Sleep -Seconds 10
    $n = (adb shell dumpsys notification --noredact | Select-String "NotificationRecord.*radio" | Measure-Object).Count
    $closed = adb logcat -d -s VideoGate:D | Select-String "decode=false"
    Assert "V4 切到纯音频台后画面区收起" ([bool]$closed) "gate closed=$([bool]$closed)"
    Assert "V4 通知栏仍只一条" ($n -eq 1) "count=$n"
    "V4 注：dumpsys audio_focus 在部分模拟器上不存在，音频焦点未单独断言，仅由切台后仍在播间接佐证"
}

if ($OnlyV -in 0, 5) {
    # V5 旋转：Activity 重建后必须重新挂上面
    adb shell settings put system accelerometer_rotation 0 | Out-Null
    adb shell settings put system user_rotation 1 | Out-Null
    Start-Sleep -Seconds 3
    adb shell settings put system user_rotation 0 | Out-Null
    Start-Sleep -Seconds 3
    $re = adb logcat -d -s VideoGate:D | Select-String "decode=true"
    Assert "V5 旋转后重新挂面" ([bool]$re) "decode=true lines=$(($re | Measure-Object).Count)"
}

if ($OnlyV -in 0, 6) {
    # V6 车机几何：1280x720@160 下播控行必须仍在首屏内
    adb shell wm density 160 | Out-Null
    adb shell wm size 1280x720 | Out-Null
    adb shell settings put system user_rotation 0 | Out-Null
    Start-Sleep -Seconds 3
    Enter-Station "人间卫视"
    $nodes = Dump-Ui "v6"
    $ctrls = $nodes | Where-Object { $_.Descr -in @("上一个电台", "下一个电台", "继续播放", "暂停") }
    $maxY = ($ctrls | Measure-Object -Property Y2 -Maximum).Maximum
    Assert "V6 播控行在首屏内" ($ctrls.Count -ge 2 -and $maxY -le 720) "controls=$($ctrls.Count) maxY=$maxY (屏高 720)"
    adb shell wm size reset | Out-Null
    adb shell wm density reset | Out-Null
}

# ---------- 全屏与旋转入口（本脚本内 F1–F5，对应计划的 V8–V11 与第一批 F6–F12）----------
#
# 操作纪律（2026-11-04 起改了）：控制层现在**常驻**，不再需要“先点画面唤出”。
# 旧脚本里那些 `input tap 540 350` 是唤出用的，现在留着反而会把按钮收起来，
# 导致“全屏按钮不存在”的假缺陷 —— 全屏项必须先 dump 再点，不要先 tap。
# （上一版纪律是反过来的：当时 3 秒自动收起，拆两条命令做必然超窗。）

function Png-Size($path) {
    # 用 PNG 头取尺寸：screencap 的宽高直接反映当前方向，比 dumpsys 字段可靠
    $b = [System.IO.File]::ReadAllBytes($path)
    @{ W = ($b[16] * 16777216) + ($b[17] * 65536) + ($b[18] * 256) + $b[19]
       H = ($b[20] * 16777216) + ($b[21] * 65536) + ($b[22] * 256) + $b[23] }
}

function Tap-Fullscreen-Entry {
    # 按钮坐标从当前 dump 取，不写死（分辨率/密度一变就不准）
    $btn = Dump-Ui "fbtn" | Where-Object { $_.Descr -eq "全屏" } | Select-Object -First 1
    if (-not $btn) { return $null }
    adb shell input tap $btn.CX $btn.CY | Out-Null
    return $btn
}

function Row-Span($tag) {
    # 用截图量“非背景像素的行跨度”，不用 uiautomator 的节点 bounds：
    # 本项目已实测 bounds 会裁切失真（忽高忽矮是裁切，稳定偏矮才是缺陷）。
    # 背景取采样直方图的众数，不取左上角单点 —— 画面真独占时四角都是画面内容。
    $p = Join-Path $tmp "$tag.png"
    adb shell rm -f /sdcard/s.png | Out-Null
    adb shell screencap -p /sdcard/s.png | Out-Null
    if ((adb shell ls /sdcard/s.png 2>$null) -match "No such file") { throw "screencap 失败（$tag）" }
    adb pull /sdcard/s.png $p | Out-Null
    Add-Type -AssemblyName System.Drawing
    $bmp = [System.Drawing.Bitmap]::FromFile($p)
    try {
        $hist = @{}
        $rows = @()
        for ($y = 0; $y -lt $bmp.Height; $y += 4) {
            $line = @()
            for ($x = 0; $x -lt $bmp.Width; $x += 8) {
                $c = $bmp.GetPixel($x, $y)
                $key = "$($c.R),$($c.G),$($c.B)"
                if ($hist.ContainsKey($key)) { $hist[$key]++ } else { $hist[$key] = 1 }
                $line += $c
            }
            $rows += ,@($y, $line)
        }
        $bgKey = ($hist.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1).Key
        $bp = $bgKey -split ","
        $bgR = [int]$bp[0]; $bgG = [int]$bp[1]; $bgB = [int]$bp[2]
        $top = -1; $bot = -1
        foreach ($r in $rows) {
            $y = $r[0]; $diff = $false
            foreach ($c in $r[1]) {
                if ([Math]::Abs($c.R - $bgR) + [Math]::Abs($c.G - $bgG) + [Math]::Abs($c.B - $bgB) -gt 40) { $diff = $true; break }
            }
            if ($diff) { if ($top -lt 0) { $top = $y }; $bot = $y }
        }
        $span = if ($top -lt 0) { 0 } else { ($bot - $top + 4) * 4 }   # 采样步长 4px，换算回真实行高
        return @{ span = [Math]::Min($span, $bmp.Height); h = $bmp.Height; bg = $bgKey }
    } finally { $bmp.Dispose() }
}

function Shot-Size($tag) {
    $p = Join-Path $tmp "$tag.png"
    adb shell screencap -p /sdcard/s.png | Out-Null
    adb pull /sdcard/s.png $p | Out-Null   # 必须走 pull：PS 的 `>` 会按文本重写 PNG 字节
    return Png-Size $p
}

if ($OnlyV -in 0, 7) {
    # F1（计划 V8）全屏切换不得扰动闸门 —— Amendment 的主判据
    Enter-Station "人间卫视"
    $opened = adb logcat -d -s VideoGate:D | Select-String "decode=true"
    Assert "F1 前置：进入播放页已开闸" ([bool]$opened) "opened=$(@($opened).Count)"
    adb logcat -c
    $btn = Tap-Fullscreen-Entry
    Assert "F1 全屏按钮存在且可点" ($null -ne $btn) "btn=$($btn.CX),$($btn.CY)"
    Start-Sleep -Seconds 9
    $g = @(adb logcat -d -s VideoGate:D)
    Assert "F1 全屏切换未扰动闸门" ($g.Count -eq 0) "VideoGate 行数=$($g.Count)"
}

if ($OnlyV -in 0, 8) {
    # F2（计划 V8 的另一半）方向翻横 + 页面 chrome 消失
    Enter-Station "人间卫视"
    Tap-Fullscreen-Entry | Out-Null
    Start-Sleep -Seconds 9
    $sz = Shot-Size "f2"
    Assert "F2 全屏后横屏" ($sz.W -gt $sz.H) "$($sz.W)x$($sz.H)"
    $chrome = Dump-Ui "f2ui" | Where-Object { $_.Descr -eq "上一个电台" }
    Assert "F2 全屏时播控行已收起" (@($chrome).Count -eq 0) "残留=$(@($chrome).Count)"
}

if ($OnlyV -in 0, 9) {
    # F3（计划 V9）返回键先退全屏、不退页面
    Enter-Station "人间卫视"
    Tap-Fullscreen-Entry | Out-Null
    Start-Sleep -Seconds 8
    adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 5
    $sz = Shot-Size "f3"
    Assert "F3 返回一次已退全屏（回竖屏）" ($sz.H -gt $sz.W) "$($sz.W)x$($sz.H)"
    $still = Dump-Ui "f3ui" | Where-Object { $_.Descr -eq "上一个电台" }
    Assert "F3 返回一次仍在播放页" (@($still).Count -ge 1) "播控节点=$(@($still).Count)"
}

if ($OnlyV -in 0, 10) {
    # F4（计划 V10）离开页面后方向与闸门复位
    Enter-Station "人间卫视"
    Tap-Fullscreen-Entry | Out-Null
    Start-Sleep -Seconds 8
    adb shell input keyevent 4 | Out-Null; Start-Sleep -Seconds 4
    adb logcat -c
    adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 6
    $last = adb logcat -d -s VideoGate:D | Select-Object -Last 1
    Assert "F4 离开页面后收闸" ("$last" -match "decode=false") "$($last -replace '\s+',' ')"
    $sz = Shot-Size "f4"
    Assert "F4 方向已恢复竖屏" ($sz.H -gt $sz.W) "$($sz.W)x$($sz.H)"

    # F5（计划 V11）sw≥600 大屏翻转成车机档后，退出按钮必须仍在
    # 注意：smallestScreenWidthDp 是短边，旋转不改变它；只有 sw≥600 的设备会翻转，
    # 所以用 wm density 160 把这台模拟器变成 sw=1080 来复现。
    adb shell wm density 160 | Out-Null
    Enter-Station "人间卫视"
    $before = Tap-Fullscreen-Entry
    Assert "F5 竖屏手机档有全屏按钮" ($null -ne $before) "btn=$($before.CX),$($before.CY)"
    Start-Sleep -Seconds 9
    $exit = Dump-Ui "f5" | Where-Object { $_.Descr -eq "退出全屏" } | Select-Object -First 1
    Assert "F5 翻成车机档后退出按钮仍在" ($null -ne $exit) "exit=$($exit.CX),$($exit.CY)"
    adb shell wm density reset | Out-Null
}

if ($OnlyV -in 0, 11) {
    # ===== 第一批修正的主判据（对应 spec §5.2 F6–F11）=====
    # 三条纪律（都是本轮被实测推翻后写下的）：
    # 1. 几何切换必须放 try/finally：wm size/density 是设备级持久设置，中途 throw 不复位
    #    会把后续所有判据跑在车机档上。
    # 2. 不可判必须用 -Inconclusive，不许用 Assert $true 冒充 PASS。
    # 3. “能不能滚”的信号必须是节点位移，不能是文本集合 —— 播放状态文案本来就在变。
    adb shell settings put system user_rotation 0 | Out-Null
    adb shell wm density 160 | Out-Null
    adb shell wm size 1280x720 | Out-Null
    try {

    # F6 车机几何下必须有全屏入口 —— 用户报的 bug 本体。
    # 控制层现在常驻，**不要先点画面**（那会把按钮收起来，造出"没有入口"的假缺陷）。
    Enter-Station "人间卫视"
    $car = Dump-Ui "f6" | Where-Object { $_.Descr -eq "全屏" } | Select-Object -First 1
    Assert "F6 车机形态有全屏入口" ($null -ne $car) "btn=$($car.CX),$($car.CY)"

    # F6b 车机不该给旋转按钮（恒横屏，给了就是按了没反应）
    $carRot = Dump-Ui "f6r" | Where-Object { $_.Descr -eq "切换横竖屏" } | Select-Object -First 1
    Assert "F6b 车机不给旋转按钮" ($null -eq $carRot) $(if ($carRot) { "误现=$($carRot.CX),$($carRot.CY)" } else { "未误现" })

    # F7 车机全屏 = 画面独占面板（C1 那个“半屏画面半屏台名”的守卫）
    if (-not $car) {
        Assert "F7 车机全屏后细节区块为 0" $false "CASCADE：F6 没拿到入口，进不了全屏" -Inconclusive
        Assert "F7 画面行跨度占满面板" $false "CASCADE：同上" -Inconclusive
        Assert "F7 画面仍在解码" $false "CASCADE：同上" -Inconclusive
    } else {
        # 出帧基线：先在**非全屏**下量一次运动。没这条基线就分不清“流自己冻住了”
        # 与“全屏这一下打断了画面”——本轮就是这么把前者报成了 FAIL。
        # （靠状态文案判“有没有出帧”不够格：流冻住时未必会显示缓冲/换源字样）
        $baseMotion = Shot-Diff "f7base" 1500
        # 基线：点全屏之前有没有 Source error。没基线就分不开"进全屏前就断了"与
        # "全屏这一下把流搞断了"，两者在 logcat 里长得一模一样。
        $errBefore = adb logcat -d | Select-String "ExoPlaybackException: Source error" | Select-Object -Last 1
        adb shell input tap $car.CX $car.CY | Out-Null
        Start-Sleep -Seconds 8
        $f7 = Dump-Ui "f7"
        $left = $f7 | Where-Object { $_.Text -like "*人间卫视*" -or $_.Descr -eq "上一个电台" }
        Assert "F7 车机全屏后细节区块为 0" (@($left).Count -eq 0) "残留=$(@($left).Count) 节点总数=$(@($f7).Count)"

        # 画面行跨度用截图量，不用节点 bounds（bounds 会裁切失真）；
        # 这条不依赖流是否稳定，所以流断的时候它仍然有效。
        $rs = Row-Span "f7span"
        Assert "F7 画面行跨度占满面板" (($rs.span / [double]$rs.h) -ge 0.85) "跨度=$($rs.span)/$($rs.h) = $([math]::Round(100 * $rs.span / $rs.h, 1))%"

        $stalled = [bool](Dump-Ui "f7st" | Where-Object { $_.Text -match "缓冲|尝试节目源" })
        $mv = Shot-Diff "f7" 1500
        $errNow = adb logcat -d | Select-String "ExoPlaybackException: Source error" | Select-Object -Last 1
        if (-not $baseMotion.changed) {
            # 非全屏也静止：这台机器上此刻根本没有帧在动，全屏判据无从对比
            Assert "F7 画面仍在解码" $false "INCONCLUSIVE：非全屏基线也静止（base=$($baseMotion.changed)/$($baseMotion.total)），流本身未出帧，与换槽无关" -Inconclusive
        } elseif ($stalled) {
            Assert "F7 画面仍在解码" $false "流未出帧（状态文案是缓冲/换源），本判据在此不可判" -Inconclusive
        } elseif ($mv.changed) {
            Assert "F7 画面仍在解码" $true "changed=$($mv.changed) 基线=$($baseMotion.changed)"
        } elseif ($errNow -and -not $errBefore) {
            Assert "F7 画面仍在解码" $false "进全屏后才出现 Source error，需人工分辨是谁先动的手" -Inconclusive
        } else {
            Assert "F7 画面仍在解码" $false "基线有帧在动、进全屏后静止且无新增 Source error —— 独占分支可能打断了画面"
        }
    }
    adb shell input keyevent 4 | Out-Null   # 先退全屏再改几何，否则带着独占态回手机档
    Start-Sleep -Seconds 4
    } finally {
        adb shell wm size reset | Out-Null
        adb shell wm density reset | Out-Null
        Start-Sleep -Seconds 3
    }

    # F9 控制层常驻：进入后等 12 秒（远大于旧的 3 秒阈值）按钮仍在
    Enter-Station "人间卫视"
    Start-Sleep -Seconds 12
    $still = Dump-Ui "f9" | Where-Object { $_.Descr -eq "全屏" } | Select-Object -First 1
    Assert "F9 控制层不再定时自动收起" ($null -ne $still) "btn=$($still.CX),$($still.CY)"

    # F10 手机全屏态有旋转按钮，按下后截图宽高互换且仍是全屏
    # 不复用 F9 的旧坐标：那中间隔了一次 Enter-Station 与 12 秒等待，布局可能已被
    # 缓冲/换源提示推下去。先 dump 再点（本脚本自己刚写下的纪律）。
    $f10btn = Tap-Fullscreen-Entry
    if (-not $f10btn) {
        Assert "F10 手机全屏有旋转按钮" $false "CASCADE：拿不到全屏入口，进不了全屏" -Inconclusive
        Assert "F10 按旋转后宽高互换" $false "CASCADE：同上" -Inconclusive
        Assert "F10 旋转后仍是全屏（退出入口没丢）" $false "CASCADE：同上" -Inconclusive
    } else {
        Start-Sleep -Seconds 8
        $rot = Dump-Ui "f10" | Where-Object { $_.Descr -eq "切换横竖屏" } | Select-Object -First 1
        Assert "F10 手机全屏有旋转按钮" ($null -ne $rot) "btn=$($rot.CX),$($rot.CY)"
        $szL = Shot-Size "f10land"
        if ($rot) { adb shell input tap $rot.CX $rot.CY | Out-Null }
        Start-Sleep -Seconds 6
        $szP = Shot-Size "f10port"
        Assert "F10 按旋转后宽高互换" (($szL.W -gt $szL.H) -and ($szP.H -gt $szP.W)) "$($szL.W)x$($szL.H) -> $($szP.W)x$($szP.H)"
        $afterRot = Dump-Ui "f10b" | Where-Object { $_.Descr -eq "退出全屏" } | Select-Object -First 1
        Assert "F10 旋转后仍是全屏（退出入口没丢）" ($null -ne $afterRot) ""
    }
    adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 5

    # F11 手机非全屏时从画面区域滑动仍能翻页（B2 基线，也是第二批装拖动手势前的对照）
    # 信号必须是“稳定文本的 y 坐标变了”（节点位移），不能是“文本集合变了”：
    # 播放状态文案（缓冲中/正在尝试节目源）本来就在变，用它当信号会同时造出
    # 假 FAIL（本轮就是这么把“测法无效”报成缺陷的）与假 PASS。
    # 另需硬前置：dump 里没有 scrollable=true 的容器时，“没的可滚”要量出来，不能倒推。
    Enter-Station "人间卫视"
    $s = Get-ScreenSize
    $xc = [int]($s.W / 2)

    $n1 = Dump-Ui "f11n0"
    $scroller = @($n1 | Where-Object { $_.Scrollable })
    Assert "F11 前置：本页有可滚容器" ($scroller.Count -ge 1) "scrollable 节点数=$($scroller.Count)（为 0 则本页不溢出，F11 不可判）" -Inconclusive:($scroller.Count -lt 1)

    if ($scroller.Count -lt 1) {
        Assert "F11 从画面内滑动可翻页" $false "INCONCLUSIVE：无可滚容器，测不出滑动" -Inconclusive
    } else {
        $sig = { param($nodes)
            ($nodes | Where-Object { $_.Text -and $_.Text -notmatch "缓冲|尝试节目源|正在播放|正在播出" }
                    | ForEach-Object { "$($_.Text)@$($_.Y1)" }) -join "|"
        }

        # 对照组：从画面外（下方）上滑
        $c1 = & $sig (Dump-Ui "f11c1")
        adb shell input swipe $xc ([int]($s.H * 0.78)) $xc ([int]($s.H * 0.28)) 400 | Out-Null
        Start-Sleep -Seconds 4
        $c2 = & $sig (Dump-Ui "f11c2")
        $ctrlChanged = ($c1 -ne $c2)

        # 实验组：先滑回顶部并等惯性停（fling 残余会被算进变化），再从画面内上滑
        adb shell input swipe $xc ([int]($s.H * 0.28)) $xc ([int]($s.H * 0.78)) 400 | Out-Null
        Start-Sleep -Seconds 5
        $p1 = & $sig (Dump-Ui "f11p1")
        adb shell input swipe $xc ([int]($s.H * 0.25)) $xc ([int]($s.H * 0.08)) 400 | Out-Null
        Start-Sleep -Seconds 4
        $p2 = & $sig (Dump-Ui "f11p2")
        $picChanged = ($p1 -ne $p2)

        if (-not $ctrlChanged) {
            Assert "F11 从画面内滑动可翻页" $false "INCONCLUSIVE：对照组（画面外同距）也没位移——本页实际滑不动" -Inconclusive
        } else {
            Assert "F11 从画面内滑动可翻页（不抢滚动）" $picChanged "对照有位移而画面内无位移则手势层吃了竖直滑动"
        }
    }
}

""
"===== 验收汇总 ====="
$results | Format-Table -AutoSize Check, Result, Detail

# V7（R8 混淆回归）不在本脚本内自动跑：release 产物名带版本号且需签名，
# 手工步骤：.\gradlew.bat :app:assembleRelease → 装产物 → 复跑 -OnlyV 2 / 3 / 6，
# 判定以 mapping.txt 为准，不看堆栈字符串猜。
