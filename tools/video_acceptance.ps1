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
    adb shell uiautomator dump /sdcard/ui.xml | Out-Null
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

# 环境前置：飞行模式会让所有取样变成「无网络」的假失败（本轮实测踩过）
if ((adb shell settings get global airplane_mode_on).Trim() -eq "1") {
    adb shell cmd connectivity airplane-mode disable | Out-Null
    adb shell svc wifi enable | Out-Null
    Start-Sleep -Seconds 6
}
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

$results = @()

function Assert($name, $ok, $detail) {
    $script:results += [pscustomobject]@{ Check = $name; Result = $(if ($ok) { "PASS" } else { "FAIL" }); Detail = $detail }
    "$name -> $(if ($ok) { 'PASS' } else { 'FAIL' }) :: $detail"
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

""
"===== 验收汇总 ====="
$results | Format-Table -AutoSize Check, Result, Detail

# V7（R8 混淆回归）不在本脚本内自动跑：release 产物名带版本号且需签名，
# 手工步骤：.\gradlew.bat :app:assembleRelease → 装产物 → 复跑 -OnlyV 2 / 3 / 6，
# 判定以 mapping.txt 为准，不看堆栈字符串猜。
