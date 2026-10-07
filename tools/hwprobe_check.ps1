param([string]$pkg = "com.radio.chinese.debug")
# 断言：按键诊断页下方有「硬件能力」只读探测区，五条通道各自成行，且可重新探测。
# 目的：在车上打开这一页就能判断硬件收音机（FM/AM）有没有对第三方 App 开放接口，不需要 adb。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
# 先清掉上一轮残留的按键绑定/记录，再补通知权限（pm clear 会把权限一起抹掉）
& $adb shell pm clear $pkg | Out-Null
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

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
function Has($lines, [string]$text) { ($lines -match [regex]::Escape($text)).Count -gt 0 }

$fail = 0
$capFail = 0
function Assert([string]$id, [bool]$ok, [string]$detail) {
  if ($ok) { Write-Output ("PASS  {0}  {1}" -f $id, $detail) }
  else { Write-Output ("FAIL  {0}  {1}" -f $id, $detail); $script:fail++ }
}

# === 连点「关于」7 次解锁并进入按键诊断页 ===
& $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
Start-Sleep -Seconds 8
Tap-Text (Dump "hw_h") "设置" | Out-Null; Start-Sleep -Seconds 5
for ($i = 0; $i -lt 7; $i++) { Tap-Text (Dump "hw_t$i") "关于" | Out-Null; Start-Sleep -Seconds 1 }
Assert "1-进入诊断页" (Tap-Text (Dump "hw_s2") "按键诊断") "连点后点「按键诊断」"
Start-Sleep -Seconds 6

# === 硬件能力探测区 ===
$pg = Dump "hw_page"
Assert "2-有硬件能力区块" (Has $pg "硬件能力") "页面出现「硬件能力」分区"
Assert "3-有系统环境行" (Has $pg "运行环境") "有系统/ROM 环境行"
Assert "4-有调谐器特性行" (Has $pg "调谐器特性") "探测 hasSystemFeature / feature 枚举"
Assert "5-有TvInput行" (Has $pg "TvInput") "探测 TvInputManager 输入服务"
Assert "6-有音频设备行" (Has $pg "音频设备") "探测音频输入输出设备（TYPE_FM_TUNER 等）"
Assert "7-有收音机应用行" (Has $pg "收音机应用") "探测已装 FM 应用"
Assert "8-有系统属性行" (Has $pg "系统属性") "探测厂商 getprop 线索"
Assert "9-有综合判断" (Has $pg "综合判断") "给出可读结论而不是让人自己拼"
Assert "10-有重新探测按钮" (Has $pg "重新探测") "允许换一台车/换 ROM 后重跑"

# === 重新探测不崩、结果仍在；且没把按键记录区改坏 ===
Assert "11-点重新探测" (Tap-Text (Dump "hw_p1") "重新探测") "点「重新探测」"
Start-Sleep -Seconds 6
$again = Dump "hw_p2"
Assert "12-重探后仍有该区" ((Has $again "硬件能力") -and (Has $again "综合判断")) "重探后硬件能力区完好"
& $adb shell input keyevent 24 | Out-Null   # 音量键：确认按键记录通道没被改动影响
Start-Sleep -Seconds 3
Assert "13-按键记录仍工作" (Has (Dump "hw_key") "VOLUME_UP") "按音量键仍出现在记录区"

# === 误报即失败 ===
# 模拟器无广播调谐器，所以这一组要求它明确说“无信号”。曾实测到它把蜂窝 radio、自家包名、
# adb 公钥 base64 里凑巧含 fm 的串都当线索，结论变成“有线索”——误导比不报更坏。
$noise = @("telephony", "com.android.emulator.radio", "bluetooth.profile", "qemu.adb.pubkey", "com.radio.chinese", "疑似调谐设备")
$hitNoise = @($noise | Where-Object { Has $again $_ })
Assert "14-不把无关信号当线索" ($hitNoise.Count -eq 0) "硬件区不得出现蜂窝/自家包/蓝牙等噪词（实际命中：$($hitNoise -join ',')）"
Assert "15-调谐器特性不命中蜂窝" ((-not (Has $again "调谐器特性：命中")) -or (-not (Has $again "telephony"))) "feature 匹配需排除 telephony.*.radio"
Assert "16-结论明确无信号" (Has $again "都无信号") "无硬件时应给确定否定结论，而不是含糊其辞"

if ($capFail -gt 0) { Write-Output ("---- 共 {0} 次抓取失败，本次结果不可信（设备掉线？）----" -f $capFail) }
Write-Output ("---- 结果: {0} 项失败 ----" -f $fail)
exit ([int](($fail -gt 0) -or ($capFail -gt 0)))
