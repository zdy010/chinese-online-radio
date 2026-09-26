param([string]$pkg = "com.radio.chinese.debug")
# 断言：节目源管理里「标记无效」的电台，切台时不能被切到。
# 流程：把内置第 2 台（经济之声）标记无效 → 从第 1 台按下一曲 → 应跳到第 3 台而非第 2 台 → 复原。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
# 预授通知权限：全新安装首启会弹 POST_NOTIFICATIONS 授权框盖住首页
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

$json = Get-Content (Join-Path $root "app\src\main\assets\radio_stations.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$names = @{}
foreach ($s in $json.stations) { $names[$s.name] = $true }
$first = $json.stations[0].name    # 中国之声
$second = $json.stations[1].name   # 经济之声（本用例把它标记无效）
$third = $json.stations[2].name    # 期望下一曲跳到这里

function Dump([string]$name) {
  & pwsh -File (Join-Path $PSScriptRoot "cap.ps1") -name $name | Out-Null
  $p = Join-Path $out "$name.txt"
  if (-not (Test-Path $p)) { $script:capFail++; Write-Warning "抓取失败：$name"; return @() }
  Get-Content $p -Encoding UTF8
}
function Dump-Until([string]$name, [string]$text, [int]$tries = 8) {
  $l = $null
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
function CurrentStation($lines) {
  $found = @()
  foreach ($l in $lines) {
    $q = [regex]::Matches($l, "'([^']*)'")
    if ($q.Count -lt 2) { continue }
    if ($names.ContainsKey($q[0].Groups[1].Value)) { $found += $q[0].Groups[1].Value }
  }
  ($found | Select-Object -Unique) -join ","
}
function Launch-Home {
  & $adb shell am force-stop $pkg | Out-Null
  & $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
  Start-Sleep -Seconds 8
}

$fail = 0
$capFail = 0
function Assert([string]$id, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Output ("PASS  {0}  {1}" -f $id, $detail) }
  else { Write-Output ("FAIL  {0}  {1}" -f $id, $detail); $script:fail++ }
}

# === 步骤一：进入 设置 → 节目源管理，把 $second 标记无效 ===
Launch-Home
Assert "1-进设置" (Tap-Text (Dump "iv_home") "设置") "点首页右上角设置"
Start-Sleep -Seconds 5
Assert "2-进节目源管理" (Tap-Text (Dump "iv_set") "节目源管理") "设置页点「节目源管理」"
Start-Sleep -Seconds 5
# 管理页是长列表，$second 在顶部附近；轮询到它出现再点
Assert "3-找到$second" (Tap-Text (Dump-Until "iv_mg" $second) $second) "点列表里的「$second」打开操作对话框"
Start-Sleep -Seconds 3
Assert "4-标记为无效" (Tap-Text (Dump "iv_dlg") "标记为无效") "对话框点「标记为无效」"
Start-Sleep -Seconds 3

# === 步骤二：回到首页，起播 $first，按下一曲，期望跳过无效的 $second 直达 $third ===
Launch-Home
Assert "5-起播$first" (Tap-Text (Dump-Until "iv_home" $first) $first) "首页点「$first」"
Start-Sleep -Seconds 10
$cur = CurrentStation (Dump "iv_play")
Assert "6-当前是$first" ($cur -eq $first) "起播后当前电台=$cur（期望 $first）"
& $adb shell input keyevent 87 | Out-Null   # KEYCODE_MEDIA_NEXT
$after = ""
for ($i = 0; $i -lt 4; $i++) { Start-Sleep -Seconds 6; $after = CurrentStation (Dump "iv_next$i"); if ($after -eq $third) { break } }
Assert "7-切台跳过无效台" ($after -eq $third) "按下一曲后当前电台=$after（期望 $third，绝不能是已标无效的 $second）"

# === 步骤三：复原，把 $second 恢复为有效（避免污染后续测试与真机状态） ===
Launch-Home
Tap-Text (Dump "iv_rh") "设置" | Out-Null; Start-Sleep -Seconds 5
Tap-Text (Dump "iv_rs") "节目源管理" | Out-Null; Start-Sleep -Seconds 5
Tap-Text (Dump-Until "iv_rm" $second) $second | Out-Null; Start-Sleep -Seconds 3
$restored = Tap-Text (Dump "iv_rd") "恢复为有效"
Start-Sleep -Seconds 3
Assert "8-已复原$second" $restored "复原：管理页把「$second」恢复为有效"

if ($capFail -gt 0) { Write-Output ("---- 共 {0} 次抓取失败，本次结果不可信（设备掉线？）----" -f $capFail) }
Write-Output ("---- 结果: {0} 项失败 ----" -f $fail)
exit ([int](($fail -gt 0) -or ($capFail -gt 0)))
