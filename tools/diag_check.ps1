param([string]$pkg = "com.radio.chinese.debug")
# 断言：设置页有隐藏的「按键诊断」入口，且该页能显示出车机/方控送来的按键。
# 用途：上车按一遍方向盘滚轮，就能判断它走的是标准键还是私有通道。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
# 预授通知权限：全新安装首启会弹 POST_NOTIFICATIONS 授权框盖住首页
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

function Dump([string]$name) {
  & pwsh -File (Join-Path $PSScriptRoot "cap.ps1") -name $name | Out-Null
  $p = Join-Path $out "$name.txt"
  if (-not (Test-Path $p)) { $script:capFail++; Write-Warning "抓取失败：$name"; return @() }
  Get-Content $p -Encoding UTF8
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

$fail = 0
$capFail = 0
function Assert([string]$id, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Output ("PASS  {0}  {1}" -f $id, $detail) }
  else { Write-Output ("FAIL  {0}  {1}" -f $id, $detail); $script:fail++ }
}

& $adb shell am force-stop $pkg | Out-Null
& $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
Start-Sleep -Seconds 10

# 变量不能叫 $home —— $HOME 是 PowerShell 只读内置变量，赋值会静默失败
$homeUi = Dump "dc_home"
Assert "1-进入设置页" (Tap-Text $homeUi "设置") "点击首页右上角设置"
Start-Sleep -Seconds 6
$set = Dump "dc_set"
Assert "2-设置页可见「关于」" (($set -match "'关于'").Count -gt 0) "设置页有「关于」行"

# 操作说明必须把绑定语义说准：同一个键一旦绑定就以绑定为准，“默认不会被顶掉”那种说法会让人以为两者共存
Assert "2c-操作说明讲清绑定语义" (($set -match "以绑定为准").Count -gt 0) "操作说明应说明未绑的键照默认、绑过的键以绑定为准"

# 操作说明就近写在设置页，标题带版本号（SectionHeader 为一个文本节点，形如 '操作说明 v1.0.89'）
Assert "2b-设置页有操作说明+版本号" (($set -match "操作说明 v\d").Count -gt 0) "设置页应出现「操作说明 v版本号」标题"

# 默认不显示诊断入口：只计「入口本身」（ListItem 标题字段恰为'按键诊断'），
# 因为操作说明正文里也会提到“按键诊断”，不能当成入口已泄露。
Assert "3-默认隐藏诊断入口" (($set -match "'按键诊断'").Count -eq 0) "未点够次数时不应出现恰为'按键诊断'的入口行"

# 连点「关于」7 次
for ($i = 0; $i -lt 7; $i++) { Tap-Text (Dump "dc_tap$i") "关于" | Out-Null; Start-Sleep -Seconds 1 }
$set2 = Dump "dc_set2"
Assert "4-连点后出现入口" (($set2 -match "'按键诊断'").Count -gt 0) "连点 7 次后应出现恰为'按键诊断'的入口行"

Assert "5-进入诊断页" (Tap-Text $set2 "按键诊断") "点击「按键诊断」"
Start-Sleep -Seconds 4
$diag = Dump "dc_diag"
Assert "6-诊断页就绪" (($diag -match "清空").Count -gt 0) "诊断页含「清空」按钮"

# 从桌面重新拉起应用（singleTop 的 onNewIntent）发的是带 ACTION_MAIN 的 intent。
# 注意必须带上 -a/-c：光用 -n 组件启动的 intent action 为 null，复现不出真实场景。
# 要是记成「Intent android.intent.action.MAIN」就会让人以为某个物理键有信号（实车上误判过）
& $adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
Start-Sleep -Seconds 3
$dRe = Dump "dc_relaunch"
Assert "6b-重进应用记为非按键" ((($dRe -match "非按键").Count -gt 0) -and (($dRe -match "应用被重新拉起").Count -gt 0)) "onNewIntent 应记成「非按键·应用被重新拉起」，不与按键信号混为一谈"

# 音量键：走 Activity.dispatchKeyEvent 这一层
& $adb shell input keyevent 24 | Out-Null
Start-Sleep -Seconds 3
$d1 = Dump "dc_vol"
Assert "7-记录到音量键" (($d1 -match "VOLUME_UP").Count -gt 0) "按 KEYCODE_VOLUME_UP(24) 后应出现 VOLUME_UP"

# 媒体键：走 Activity.dispatchKeyEvent 或 MediaSession 这一层（车机滚轮若是标准键就在这里露面）
# KEYCODE_MEDIA_NEXT=87（别与 88=PREVIOUS 记反）
& $adb shell input keyevent 87 | Out-Null
Start-Sleep -Seconds 3
$d2 = Dump "dc_media"
$hit = (($d2 -match "MEDIA_NEXT").Count -gt 0) -or (($d2 -match "SEEK_TO_NEXT").Count -gt 0)
Assert "8-记录到媒体键" $hit "按 KEYCODE_MEDIA_NEXT(87) 后应出现 MEDIA_NEXT 或 SEEK_TO_NEXT"

# 清空
Assert "9-清空按钮可用" (Tap-Text $d2 "清空") "点击「清空」"
Start-Sleep -Seconds 2
$d3 = Dump "dc_clear"
Assert "10-清空生效" (($d3 -match "VOLUME_UP").Count -eq 0) "清空后不应再显示旧记录"

if ($capFail -gt 0) { Write-Output ("---- 共 {0} 次抓取失败，本次结果不可信（设备掉线？）----" -f $capFail) }
Write-Output ("---- 结果: {0} 项失败 ----" -f $fail)
exit ([int](($fail -gt 0) -or ($capFail -gt 0)))
