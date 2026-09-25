param(
  [int]$dpi = 160,
  [switch]$Portrait
)
# 造车机几何：只切 density 与方向。
# 不用 `wm size`——它按竖屏序归一化尺寸，且会把 user_rotation 打回竖屏，测出来的横屏是假的。
# 本机原生 1080x2424：横屏 2424x1080px，除以 density 系数即得 dp。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb wait-for-device | Out-Null
& $adb shell wm size reset | Out-Null
& $adb shell settings put system accelerometer_rotation 0 | Out-Null
& $adb shell wm density $dpi | Out-Null
if ($Portrait) { & $adb shell settings put system user_rotation 0 | Out-Null }
else { & $adb shell settings put system user_rotation 1 | Out-Null }
Start-Sleep -Seconds 2
$factor = $dpi / 160.0
if ($Portrait) { $pw = 1080; $ph = 2424 } else { $pw = 2424; $ph = 1080 }
$wDp = [math]::Round($pw / $factor); $hDp = [math]::Round($ph / $factor)
$sw = [math]::Round([math]::Min($pw, $ph) / $factor)
"env: ${pw}x${ph}px @${dpi}dpi = ${wDp}x${hDp}dp  sw=${sw}dp  factor=$factor  方向=$(if ($Portrait) {'竖屏'} else {'横屏'})"
"auto 判定: $(if ($sw -ge 600 -and -not $Portrait) {'Car'} elseif ($sw -ge 600) {'Phone(竖屏)'} else {'Phone(sw<600，需手动切车机)'})"
"断言请传: -density $factor"
