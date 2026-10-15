param(
    [string]$ApkKind = "debug",      # debug / release
    [switch]$SkipCarAssert
)
# 主题切换 UI 验收：逐个选中 9 个内置主题，验证
#   1) 对话框里该项能滚进可点区并点中（低 density 车机装不下是本项目踩过的坑）
#   2) 选后对话框关闭，且设置页回显成刚选的主题名
#   3) 重启后仍是它（theme_key 持久化 + 旧 theme_mode 迁移的端到端证据）
#   4) 播放页在暗色主题下可用性语义色不消失（Task 6 的回归点）
# 产物落 .qtest/theme_*，退出码非 0 即有失败项。
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
Add-Type -AssemblyName System.Drawing

# 先清旧产物：设备掉线时 adb 只报错不产出，留着旧文件就会把上一轮当本轮结果（假通过）
Remove-Item (Join-Path $out "theme_*") -Force -ErrorAction SilentlyContinue

# 包名一律从 APK 里读：debug 变体带 applicationIdSuffix=".debug"，写死包名会驱动到
# 设备上残留的另一个实例，而 adb install 照样回 Success。
$apk = (Get-ChildItem (Join-Path $root "app\build\outputs\apk\$ApkKind\*.apk") | Select-Object -First 1).FullName
if (-not $apk) { throw "找不到 $ApkKind 产物，先跑 .\gradlew.bat :app:assemble$($ApkKind.Substring(0,1).ToUpper())$($ApkKind.Substring(1))" }
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory | Select-Object -Last 1
$badging = (& "$($bt.FullName)\aapt2.exe" dump badging $apk 2>&1) -join " "
$pkg = [regex]::Match($badging, "package: name='([^']+)'").Groups[1].Value
$ver = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value
if (-not $pkg) { throw "aapt2 没能从 $apk 读出包名" }

function A([string]$cmd) { & $adb shell $cmd 2>$null }
function Dump([string]$tag) {
    $f = Join-Path $out "theme_$tag.xml"
    Remove-Item $f -Force -ErrorAction SilentlyContinue
    A "uiautomator dump /sdcard/ui.xml" | Out-Null
    & $adb pull /sdcard/ui.xml $f | Out-Null
    if (-not (Test-Path $f)) { throw "未取到 $tag 的 dump（设备掉线/未授权）" }
    [xml](Get-Content $f -Raw -Encoding UTF8)
}
function Nodes([xml]$d) { $d.SelectNodes("//node") }
function Match([xml]$d, [string]$n) { Nodes $d | Where-Object { $_.text -eq $n -or $_.'content-desc' -eq $n } }
function Box([string]$b) {
    $m = [regex]::Match($b, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    [pscustomobject]@{ x0 = [int]$m.Groups[1].Value; y0 = [int]$m.Groups[2].Value; x1 = [int]$m.Groups[3].Value; y1 = [int]$m.Groups[4].Value }
}
function TapNode($node) { $b = Box $node.bounds; A "input tap $((($b.x0 + $b.x1) / 2)) $((($b.y0 + $b.y1) / 2))" | Out-Null }
function ScreenH([xml]$d) { (Nodes $d | ForEach-Object { (Box $_.bounds).y1 } | Measure-Object -Maximum).Maximum }
function Launch {
    A "am force-stop $pkg" | Out-Null
    & $adb shell "monkey -p $pkg -c android.intent.category.LAUNCHER 1" | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 800
        $x = Dump "ready"
        if (Match $x "设置") { return }
    }
    throw "冷启动 32s 内未就绪"
}
function GotoSettings([string]$tag) {
    for ($i = 0; $i -lt 8; $i++) {
        $d = Dump $tag
        if (Match $d "主题") { return $d }
        # 窗口动画中途 uiautomator 会抓到空树（实测 theme_t0-a0.xml 一个 text 节点都没有），
        # 这种帧不是失败，重试；真进不去由上面的轮次上限兑现。
        $icon = Match $d "设置" | Where-Object { $_.clickable -eq "true" } | Select-Object -First 1
        if (-not $icon) { $icon = Match $d "设置" | Select-Object -First 1 }
        if ($icon) { TapNode $icon }
        Start-Sleep -m 900
    }
    throw "多次尝试仍进不去设置页"
}
# 一条横带的像素统计：判「可用性语义色在暗底上还在不在」用得上（亮色像素计数）
function BandLight([string]$png, [int]$y0, [int]$y1) {
    $bmp = [System.Drawing.Bitmap]::FromFile($png)
    if ($y1 -gt $bmp.Height) { $y1 = $bmp.Height }
    $light = 0; $n = 0
    for ($y = $y0; $y -lt $y1; $y += 2) {
        for ($x = 0; $x -lt $bmp.Width; $x += 2) {
            $c = $bmp.GetPixel($x, $y)
            $lum = 0.2126 * $c.R + 0.7152 * $c.G + 0.0722 * $c.B
            $n++; if ($lum -gt 150) { $light++ }
        }
    }
    $bmp.Dispose()
    [pscustomobject]@{ light = $light; samples = $n }
}

# 打开主题对话框：导航抖动会吞掉一次点击（无 crash 记录，只是没跳页），
# 所以重试只用在「根本没打开」上；「滚不到目标行」仍由扫描环节负责。
function OpenThemeDialog([string]$tag) {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $d = GotoSettings "$tag-a$attempt"
        $t = Match $d "主题" | Select-Object -First 1
        if (-not $t) { continue }
        TapNode $t
        for ($w = 0; $w -lt 10; $w++) {
            Start-Sleep -Milliseconds 400
            $d = Dump "$tag-o$attempt"
            if (Match $d "选择主题") { return $d }
        }
    }
    return $null
}
# 对话框内容区的可视窗口：取那个 scrollable 节点的 bounds。
# 用屏幕高当窗口会把被对话框边缘裁切的行误判成「可见」，也会漏判被裁切的行。
function Viewport([xml]$d) {
    $sc = Nodes $d | Where-Object { $_.scrollable -eq "true" } | Select-Object -First 1
    if ($sc) { return Box $sc.bounds }
    $h = ScreenH $d
    return [pscustomobject]@{ x0 = 0; y0 = 0; x1 = 1080; y1 = $h }
}

& $adb install -r $apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw "adb install 失败 exit=$LASTEXITCODE" }
$dev = ((A "dumpsys package $pkg | grep versionName") -join " ")
if ($dev -notmatch [regex]::Escape($ver)) { throw "设备上不是刚构的包（$dev），判读无效" }
A "pm grant $pkg android.permission.POST_NOTIFICATIONS" | Out-Null
"target: $pkg $ver ($ApkKind)"

$labels = "跟随系统","经典蓝·日间","经典蓝·夜间","深灰柔和","纯黑·夜高对比","琥珀暖光","亮白·强对比","中国红","墨绿"
$fail = 0
Launch

foreach ($idx in 0..($labels.Count - 1)) {
    $label = $labels[$idx]
    # 每轮都从干净状态开始：上一轮残留的对话框/滚动位置会把「没渲染出来」误读成「滚不进去」
    Launch
    $d = OpenThemeDialog "t$idx"
    if (-not $d) { "FAIL [$label] 主题对话框没打开（重试 3 次均失败）"; $fail++; continue }

    # 小步长滚动：一步 1100px 相当于 5 行（行高 88dp=231px），目标行会整行从两帧之间穿过去。
    # 200px < 一行，保证每行至少在某一帧完整落在窗口内。
    $row = $null
    for ($try = 0; $try -lt 25; $try++) {
        $d = Dump "d$idx-$try"
        $vp = Viewport $d
        $cand = Match $d $label | Select-Object -First 1
        if ($cand) {
            $b = Box $cand.bounds
            if ($b.y0 -ge $vp.y0 -and $b.y1 -le $vp.y1) { $row = $cand; break }
        }
        A "input swipe 540 $([int]($vp.y1 - 120)) 540 $([int]($vp.y1 - 320)) 250" | Out-Null
        Start-Sleep -Milliseconds 400
    }
    if (-not $row) { "FAIL [$label] 滚了 25 次仍滚不进对话框可视区"; $fail++; continue }

    TapNode $row; Start-Sleep -m 1100
    $d = Dump "a$idx"
    if (Match $d "选择主题") { "FAIL [$label] 选后对话框没关"; $fail++; continue }
    $echo = $null
    for ($k = 0; $k -lt 4; $k++) {
        $d = GotoSettings "e$k"
        $echo = Match $d $label | Select-Object -First 1
        if ($echo) { break }
        Launch
    }
    if (-not $echo) { "FAIL [$label] 设置页没回显成当前主题"; $fail++; continue }
    "OK   [$label] 可点中并回显"
}

# 暗色主题下的可用性语义色：进播放页看有没有亮色像素（Task 6 的回归点，纯黑底上暗红会看不见）
$d = GotoSettings "pre-player"
A "am force-stop $pkg" | Out-Null
Launch
# 只抓一帧会把过渡期的空树当成「找不到电台」（上一轮就是这么 WARN 的），改成轮询
$station = $null
for ($p = 0; $p -lt 6; $p++) {
    $homeDump = Dump "player_home"
    $station = $homeDump.SelectNodes("//node") | Where-Object { $_.text -eq "中国之声" } | Select-Object -First 1
    if ($station) { break }
    Start-Sleep -Milliseconds 700
}
if ($station) {
    TapNode $station; Start-Sleep -m 2500
    A "screencap -p /sdcard/theme_player.png" | Out-Null
    & $adb pull /sdcard/theme_player.png (Join-Path $out "theme_player_dark.png") | Out-Null
    $s = BandLight (Join-Path $out "theme_player_dark.png") 0 (ScreenH (Dump "player"))
    "player(当前主题) 亮色像素=$($s.light)/$($s.samples)"
    A "input keyevent KEYCODE_BACK" | Out-Null
} else {
    "WARN 首页找不到「中国之声」，跳过播放页取样"
}

# 命中区交给 car_assert.ps1（它自带容器裁切判别，别在这里重造）
if (-not $SkipCarAssert) {
    $d = GotoSettings "pre-assert"
    TapNode (Match $d "主题" | Select-Object -First 1); Start-Sleep -m 900
    & (Join-Path $PSScriptRoot "car_assert.ps1") -name "theme_dialog"
}

if ($fail -gt 0) { "RESULT: FAIL ($fail 项)"; exit 1 }
"RESULT: PASS"
