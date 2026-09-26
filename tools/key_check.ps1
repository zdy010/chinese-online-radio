param([string]$pkg = "com.radio.chinese.debug")
# 断言：车机/方控发来的「下一曲/上一曲」媒体键应当切换电台。
# keycode 事实：KEYCODE_MEDIA_NEXT=87、KEYCODE_MEDIA_PREVIOUS=88（Android 常量，别记反）。
# 本项目无测试源码集，用 uiautomator dump 做端到端断言；产物落在 .qtest/（已 gitignore）。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
# 预授通知权限：全新安装首启会弹 POST_NOTIFICATIONS 授权框盖住首页，导致 dump 抓到弹窗而非列表
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

# 内置电台名集合，用来从 dump 里认出「当前正在播的台」
$json = Get-Content (Join-Path $root "app\src\main\assets\radio_stations.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$names = @{}
foreach ($s in $json.stations) { $names[$s.name] = $true }
$first = $json.stations[0].name
$second = $json.stations[1].name

function Dump([string]$name) {
  & pwsh -File (Join-Path $PSScriptRoot "cap.ps1") -name $name | Out-Null
  $p = Join-Path $out "$name.txt"
  if (-not (Test-Path $p)) { $script:capFail++; Write-Warning "抓取失败：$name"; return @() }
  Get-Content $p -Encoding UTF8
}

# 轮询 dump 直到某行包含 $text（应对冷启动渲染慢，避免首帧空窗导致漏点）
function Dump-Until([string]$name, [string]$text, [int]$tries = 8) {
  for ($i = 0; $i -lt $tries; $i++) {
    $l = Dump "$name$i"
    if (($l -match [regex]::Escape($text)).Count -gt 0) { return ,$l }
    Start-Sleep -Seconds 5
  }
  return ,($l)
}

function Tap-Text($lines, [string]$text) {
  foreach ($l in $lines) {
    $q = [regex]::Matches($l, "'([^']*)'")
    if ($q.Count -lt 2) { continue }
    if ($q[0].Groups[1].Value -ne $text -and $q[1].Groups[1].Value -ne $text) { continue }
    $b = [regex]::Match($l, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    if (-not $b.Success) { continue }
    $x = ([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2
    $y = ([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2
    & $adb shell input tap $x $y | Out-Null
    return $true
  }
  return $false
}

# 当前电台 = dump 里出现的、属于内置电台名集合的文本（播放页只会出现一个）
function CurrentStation($lines) {
  $found = @()
  foreach ($l in $lines) {
    $q = [regex]::Matches($l, "'([^']*)'")
    if ($q.Count -lt 2) { continue }
    if ($names.ContainsKey($q[0].Groups[1].Value)) { $found += $q[0].Groups[1].Value }
  }
  ($found | Select-Object -Unique) -join ","
}

$fail = 0
$capFail = 0
function Assert([string]$id, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Output ("PASS  {0}  {1}" -f $id, $detail) }
  else { Write-Output ("FAIL  {0}  {1}" -f $id, $detail); $script:fail++ }
}

# --- 起播内置列表第一个台（先轮询到列表渲染出来再点，避免冷启动漏点） ---
& $adb shell am force-stop $pkg | Out-Null
& $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
Start-Sleep -Seconds 8
$homeUi = Dump-Until "kc_home" $first
Assert "1-进入播放页" (Tap-Text $homeUi $first) "点击列表里的「$first」"
Start-Sleep -Seconds 12

$lines = Dump "kc_play"
$onPlayer = ($lines -match "上一个电台").Count -gt 0
Assert "2-播放页就绪" $onPlayer "播放页控件「上一个电台」可见"
$cur = CurrentStation $lines
Assert "3-起播电台正确" ($cur -eq $first) "当前电台=$cur（期望 $first）"

# --- 下一曲（KEYCODE_MEDIA_NEXT=87）：应切到第二个台 ---
& $adb shell input keyevent 87 | Out-Null
$next = ""
for ($i = 0; $i -lt 4; $i++) { Start-Sleep -Seconds 6; $next = CurrentStation (Dump "kc_next$i"); if ($next -eq $second) { break } }
Assert "4-下一曲切台" ($next -eq $second) "按 KEYCODE_MEDIA_NEXT(87) 后当前电台=$next（期望 $second）"

# --- 上一曲（KEYCODE_MEDIA_PREVIOUS=88）：从第二个台应切回第一个台 ---
# 前置状态准备：断言 4 已切到 $second 则直接继续；若未切到（RED 期），
# 用播放页 UI「下一个电台」按钮补到 $second，保证断言 5 独立于断言 4 的结果，避免假通过。
if ($next -ne $second) {
  Assert "5a-点击下一个电台" (Tap-Text (Dump "kc_prep") "下一个电台") "用播放页 UI 按钮准备前置状态"
  for ($i = 0; $i -lt 4; $i++) { Start-Sleep -Seconds 6; $next = CurrentStation (Dump "kc_prep$i"); if ($next -eq $second) { break } }
  Assert "5b-前置状态就绪" ($next -eq $second) "UI 按钮切台后当前电台=$next（期望 $second）"
}
& $adb shell input keyevent 88 | Out-Null
$prev = ""
for ($i = 0; $i -lt 4; $i++) { Start-Sleep -Seconds 6; $prev = CurrentStation (Dump "kc_prev$i"); if ($prev -eq $first) { break } }
Assert "5-上一曲切台" ($prev -eq $first) "按 KEYCODE_MEDIA_PREVIOUS(88) 后当前电台=$prev（期望 $first）"

# --- 切台后仍在播放 ---
$state = ((& $adb shell dumpsys media_session) -join "`n")
$st = [regex]::Match($state, "state=PlaybackState \{state=([A-Z_]+)").Groups[1].Value
Assert "6-切台后继续播放" ($st -eq "PLAYING") "media_session state=$st"

if ($capFail -gt 0) { Write-Output ("---- 共 {0} 次抓取失败，本次结果不可信（设备掉线？）----" -f $capFail) }
Write-Output ("---- 结果: {0} 项失败 ----" -f $fail)
exit ([int](($fail -gt 0) -or ($capFail -gt 0)))
