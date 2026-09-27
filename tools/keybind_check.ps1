param([string]$pkg = "com.radio.chinese.debug")
# 断言：车机用户可把物理按键绑到 4 个功能，且默认行为不被顶掉、恢复默认后真的失效。
# keycode 事实：KEYCODE_MEDIA_NEXT=87、KEYCODE_MEDIA_PREVIOUS=88；测试用键 STAR=17 / ESCAPE=111。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
# 从干净状态开始：先清掉上一轮可能残留的按键绑定（否则“默认未绑定”会被脏数据干扰），
# 再补授通知权限——pm clear 会把权限一起抹掉，不补首启就会弹授权框盖住首页。
& $adb shell pm clear $pkg | Out-Null
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

$json = Get-Content (Join-Path $root "app\src\main\assets\radio_stations.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$names = @{}
foreach ($s in $json.stations) { $names[$s.name] = $true }
$first = $json.stations[0].name    # 中国之声
$second = $json.stations[1].name   # 经济之声

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
function Tap-Line($lines, [string]$pattern, [int]$nth = 1) {
  $seen = 0
  foreach ($l in $lines) {
    if ($l -notmatch $pattern) { continue }
    $seen++
    if ($seen -lt $nth) { continue }
    $b = [regex]::Match($l, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    if (-not $b.Success) { continue }
    $x = ([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2
    $y = ([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2
    & $adb shell input tap $x $y | Out-Null
    return $true
  }
  return $false
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
function Wait-Station([string]$tag, [string]$expect, [int]$tries = 4) {
  $cur = ""
  for ($i = 0; $i -lt $tries; $i++) {
    Start-Sleep -Seconds 6
    $cur = CurrentStation (Dump "$tag$i")
    if ($cur -eq $expect) { break }
  }
  return $cur
}
function Launch-Home {
  & $adb shell am force-stop $pkg | Out-Null
  & $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
  Start-Sleep -Seconds 8
}
function Goto-KeySettings {
  # 先确定回到首页：上一段测完可能停在播放页，在播放页是找不到右上角「设置」的
  Launch-Home
  Tap-Text (Dump "kb_h") "设置" | Out-Null; Start-Sleep -Seconds 5
  $ok = Tap-Text (Dump-Until "kb_s" "按键设置") "按键设置"
  Start-Sleep -Seconds 4
  return $ok
}

$fail = 0
$capFail = 0
function Assert([string]$id, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Output ("PASS  {0}  {1}" -f $id, $detail) }
  else { Write-Output ("FAIL  {0}  {1}" -f $id, $detail); $script:fail++ }
}

$BIND_KEY = 17          # KEYCODE_STAR，非媒体键，用来证明"任意按键可绑"
$BIND_LABEL = "KEYCODE_STAR(17)"

Launch-Home

# === 一、入口与四个功能行 ===
Assert "1-进按键设置" (Goto-KeySettings) "设置页点「按键设置」"
$ks = Dump "kb_page"
Assert "2-四个功能可见" ((($ks -match "上一电台").Count -gt 0) -and (($ks -match "下一电台").Count -gt 0) -and (($ks -match "播放 / 暂停").Count -gt 0) -and (($ks -match "返回上一页").Count -gt 0)) "四个功能行齐备"
Assert "3-每行有录制入口" (((($ks -match "录制").Count) -ge 4) -and (($ks -match "全部恢复默认").Count -gt 0)) "每行「录制」+ 页面「全部恢复默认」"
Assert "4-默认未绑定" (($ks -match "未绑定").Count -ge 4) "未绑定时四行都显示「未绑定」"

# === 二、把 STAR(17) 绑到「下一电台」（列表第 2 行的录制） ===
Assert "5-点开下一电台的录制" (Tap-Line $ks "'录制'" 2) "点第 2 行（下一电台）的「录制」"
$dlg = Dump "kb_dlg"
Assert "6-录制对话框就绪" (($dlg -match "请按要绑定的").Count -gt 0) "出现「请按要绑定的…」提示"
& $adb shell input keyevent $BIND_KEY | Out-Null
Start-Sleep -Seconds 3
$cand = Dump "kb_cand"
Assert "7-捕获到按键候选" (Tap-Text $cand $BIND_LABEL) "候选列表出现 $BIND_LABEL 并点选绑定"
Start-Sleep -Seconds 3
$row = Dump "kb_bound"
Assert "8-行内显示绑定" (($row -match [regex]::Escape($BIND_LABEL)).Count -gt 0) "「下一电台」行已显示 $BIND_LABEL"

# === 三、自定义键真的驱动切台 ===
Launch-Home
Assert "9-起播$first" (Tap-Text (Dump-Until "kb_h2" $first) $first) "首页点「$first」"
Start-Sleep -Seconds 10
$cur = CurrentStation (Dump "kb_p0")
Assert "10-当前$first" ($cur -eq $first) "起播后当前电台=$cur"
& $adb shell input keyevent $BIND_KEY | Out-Null
$after = Wait-Station "kb_bind" $second
Assert "11-自定义键切到下一电台" ($after -eq $second) "按 $BIND_LABEL 后当前电台=$after（期望 $second）"

# === 四、默认媒体键不被顶掉 ===
& $adb shell input keyevent 88 | Out-Null
$back1 = Wait-Station "kb_def" $first
Assert "12-默认媒体键仍有效" ($back1 -eq $first) "按 KEYCODE_MEDIA_PREVIOUS(88) 后当前电台=$back1（期望 $first）"

# === 五、全部恢复默认后，自定义键不再起作用 ===
Assert "13-再进按键设置" (Goto-KeySettings) "设置页点「按键设置」"
Assert "14-点全部恢复默认" (Tap-Text (Dump "kb_rs") "全部恢复默认") "点「全部恢复默认」"
Start-Sleep -Seconds 3
$cleared = Dump "kb_cleared"
Assert "15-绑定已清空" (($cleared -match "未绑定").Count -ge 4 -and ($cleared -match [regex]::Escape($BIND_LABEL)).Count -eq 0) "四行回到「未绑定」，不再显示 $BIND_LABEL"

Launch-Home
Assert "16-起播$first" (Tap-Text (Dump-Until "kb_h3" $first) $first) "首页点「$first」"
Start-Sleep -Seconds 10
& $adb shell input keyevent $BIND_KEY | Out-Null
Start-Sleep -Seconds 8
$still = CurrentStation (Dump "kb_after")
Assert "17-恢复默认后自定义键失效" ($still -eq $first) "再按 $BIND_LABEL 后当前电台=$still（应仍是 $first）"

# === 六、返回上一页可绑 ===
# 用 KEYCODE_INFO(165)，不用 ESCAPE(111)：ESCAPE 会被系统当成返回键吃掉，
# 录制期间页面会被自己弹掉，测不出绑定。
$BACK_KEY = 165
$BACK_LABEL = "KEYCODE_INFO(165)"
Assert "18-进按键设置绑返回" (Goto-KeySettings) "设置页点「按键设置」"
Assert "19-点开返回上一页的录制" (Tap-Line (Dump "kb_gs") "'录制'" 4) "点第 4 行（返回上一页）的「录制」"
Dump "kb_wait" | Out-Null; Start-Sleep -Seconds 2
& $adb shell input keyevent $BACK_KEY | Out-Null
Start-Sleep -Seconds 3
Assert "20-捕获到候选键" (Tap-Text (Dump "kb_esc") $BACK_LABEL) "候选出现 $BACK_LABEL 并点选"
Start-Sleep -Seconds 3
Launch-Home
Tap-Text (Dump-Until "kb_h4" $first) $first | Out-Null
Start-Sleep -Seconds 10
$onPlayer = ((Dump "kb_pl") -match "上一个电台").Count -gt 0
Assert "21-已进播放页" $onPlayer "播放页控件可见"
& $adb shell input keyevent $BACK_KEY | Out-Null
Start-Sleep -Seconds 6
$gone = ((Dump "kb_back") -match "搜索电台名称或频率").Count -gt 0
Assert "22-绑定键能返回上一页" $gone "按 $BACK_LABEL 后回到电台列表（出现搜索框）"

# === 七、剩下两个槽：上一电台、播放/暂停 ===
# 探针实测：172(BOOKMARK)/207(CAPTIONS) 不到达 dispatchKeyEvent，DPAD 类键会触发导航把页面弹掉。
# 这里改用已证实可用的 STAR(17) 与 INFO(165)；绑到别的槽时存储会按「一键一功能」自动解除旧绑定。
$PREV_KEY = 17;   $PREV_LABEL = "KEYCODE_STAR(17)"
$TOGGLE_KEY = 165; $TOGGLE_LABEL = "KEYCODE_INFO(165)"

Assert "23-进按键设置补绑两槽" (Goto-KeySettings) "设置页点「按键设置」"
Tap-Line (Dump "kb_g1") "'录制'" 1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell input keyevent $PREV_KEY | Out-Null
Start-Sleep -Seconds 3
Assert "24-绑上 $PREV_LABEL" (Tap-Text (Dump "kb_g2") $PREV_LABEL) "第 1 行候选出现 $PREV_LABEL 并点选"
Start-Sleep -Seconds 3
Tap-Line (Dump "kb_g3") "'录制'" 3 | Out-Null
Start-Sleep -Seconds 2
& $adb shell input keyevent $TOGGLE_KEY | Out-Null
Start-Sleep -Seconds 3
Assert "25-绑上 $TOGGLE_LABEL" (Tap-Text (Dump "kb_g4") $TOGGLE_LABEL) "第 3 行候选出现 $TOGGLE_LABEL 并点选（同时自动解除上一段的 GO_BACK 绑定）"
Start-Sleep -Seconds 3

Launch-Home
Tap-Text (Dump-Until "kb_h5" $first) $first | Out-Null
Start-Sleep -Seconds 10
& $adb shell input keyevent 87 | Out-Null
$up = Wait-Station "kb_g5" $second
Assert "26-默认下一曲到$second" ($up -eq $second) "按媒体下一曲后当前电台=$up（期望 $second）"
& $adb shell input keyevent $PREV_KEY | Out-Null
$dn = Wait-Station "kb_g6" $first
Assert "27-自定义键能切上一电台" ($dn -eq $first) "按 $PREV_LABEL 后当前电台=$dn（期望 $first）"

& $adb shell input keyevent $TOGGLE_KEY | Out-Null
Start-Sleep -Seconds 4
$state = ((& $adb shell dumpsys media_session) -join "`n")
$s1 = [regex]::Match($state, "state=PlaybackState \{state=([A-Z_]+)").Groups[1].Value
Assert "28-自定义键能暂停" ($s1 -eq "PAUSED") "按 $TOGGLE_LABEL 后 media_session state=$s1（期望 PAUSED）"
& $adb shell input keyevent $TOGGLE_KEY | Out-Null
Start-Sleep -Seconds 4
$state2 = ((& $adb shell dumpsys media_session) -join "`n")
$s2 = [regex]::Match($state2, "state=PlaybackState \{state=([A-Z_]+)").Groups[1].Value
Assert "29-再按能恢复播放" ($s2 -eq "PLAYING") "再按 $TOGGLE_LABEL 后 state=$s2（期望 PLAYING）"

if ($capFail -gt 0) { Write-Output ("---- 共 {0} 次抓取失败，本次结果不可信（设备掉线？）----" -f $capFail) }
Write-Output ("---- 结果: {0} 项失败 ----" -f $fail)
exit ([int](($fail -gt 0) -or ($capFail -gt 0)))
