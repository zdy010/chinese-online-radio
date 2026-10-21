param([string]$Pkg = "com.radio.chinese.debug", [int[]]$Dpis = @(160, 240))
# 车机档（横屏）回归：三项功能 + 88dp 命中区。
#   功能：首页网格长按弹菜单 / 管理页搜索过滤 / 管理页长按弹菜单
#   几何：首页网格、管理页列表、弹出的管理菜单对话框三屏可点控件 >= 88dp
# 纪律同 opera_player_check.ps1：清旧产物、实时 dump、断言计数下限、硬校验真横屏。
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = $Pkg
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$fails = 0; $ran = 0
function Check($cond, [string]$what) { $script:ran++; if ($cond) { "PASS: $what" } else { "FAIL: $what"; $script:fails++ } }
function Dump([string]$name) {
  for ($i = 1; $i -le 8; $i++) {
    & $adb shell rm /sdcard/ui.xml 2>$null | Out-Null
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    if ((& $adb shell ls /sdcard/ui.xml 2>$null)) {
      Remove-Item "$out\car_$name.xml" -ErrorAction SilentlyContinue
      & $adb pull /sdcard/ui.xml "$out\car_$name.xml" | Out-Null
      $f = "$out\car_$name.xml"
      if ((Test-Path $f) -and ((Get-Item $f).Length -gt 500)) { try { return [xml](Get-Content $f -Raw -Encoding UTF8) } catch { } }
    }
    Start-Sleep -Milliseconds 1200
  }
  return [xml]'<?xml version="1.0" ?><hierarchy rotation="0"></hierarchy>'
}
function Nodes($doc) { @($doc.SelectNodes("//*")) }
function FindNode($doc, [string]$label) { Nodes $doc | Where-Object { $_.GetAttribute("content-desc") -eq $label -or $_.GetAttribute("text") -eq $label } | Select-Object -First 1 }
function Has($doc, [string]$label) { [bool](FindNode $doc $label) }
function HasTextLike($doc, [string]$pattern) { [bool]((Nodes $doc) | Where-Object { $_.GetAttribute("text") -match $pattern } | Select-Object -First 1) }
function Center($node) { $m = [regex]::Match($node.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'); @{ x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2; y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2 } }
function Tap($doc, [string]$label) { $n = FindNode $doc $label; if (-not $n) { Write-Host "  (找不到 $label)"; return $false }; $c = Center $n; & $adb shell input tap ([int]$c.x) ([int]$c.y); Start-Sleep -Milliseconds 900; return $true }
# 在列表/网格里多点长按，命中期望对话框标记即停（把对话框留着，方便随后跑 car_assert）
function LongPressOpen([int]$w, [int]$h, [string]$marker, [string]$capName) {
  $ys = @([int]($h*0.30),[int]($h*0.45),[int]($h*0.60),[int]($h*0.75))
  foreach ($xf in 0.20,0.50,0.80) {
    foreach ($y in $ys) {
      $x = [int]($w*$xf)
      & $adb shell input swipe $x $y $x $y 1000; Start-Sleep -Milliseconds 700
      $d = Dump $capName
      if (Has $d $marker) { return $d }
    }
  }
  return $null
}
function AssertCar([string]$file, [double]$factor) {
  $a = & (Join-Path $PSScriptRoot "car_assert.ps1") -InFile -name $file -density $factor -minDp 88.0
  ($a | Out-String) -split "`n" | ForEach-Object { "    $_" }
  return (($a | Out-String) -notmatch 'VIOLATIONS' -and ($a | Out-String) -notmatch 'OFFSCREEN')
}

Remove-Item "$out\car_*.xml" -ErrorAction SilentlyContinue
& $adb shell pm grant $pkg android.permission.READ_MEDIA_AUDIO 2>$null | Out-Null

foreach ($dpi in $Dpis) {
  $factor = $dpi / 160.0
  "==================== 车机档 ${dpi}dpi ===================="
  & $adb shell settings put system accelerometer_rotation 0 | Out-Null
  & $adb shell wm size reset | Out-Null
  & $adb shell wm density $dpi | Out-Null
  & $adb shell settings put system user_rotation 1 | Out-Null
  Start-Sleep -Seconds 2
  & $adb shell am force-stop $pkg; & $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
  $ready = $false
  1..20 | ForEach-Object { if (-not $ready) { Start-Sleep -Seconds 2; if (Has (Dump "boot") "音频库") { $ready = $true } } }
  if (-not $ready) { "FAIL: ($dpi) 冷启动未就绪"; $script:fails++; $script:ran++; continue }
  # 硬校验真横屏：拿竖屏评 88dp 会整批假违规
  $land = $false
  1..5 | ForEach-Object { if (-not $land) { $r = [regex]::Match((Dump "orient").DocumentElement.FirstChild.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'); if ([int]$r.Groups[3].Value -gt [int]$r.Groups[4].Value) { $land = $true } else { & $adb shell settings put system user_rotation 1 | Out-Null; Start-Sleep -Seconds 2 } } }
  if (-not $land) { "FAIL: ($dpi) 无法进入横屏，跳过不给假通过"; $script:fails++; $script:ran++; continue }
  # 宽高必须取旋转后的实际屏（wm size 回的是竖屏物理序 1080x2424，拿它会把手势点到屏外）
  $rb = [regex]::Match((Dump "wh").DocumentElement.FirstChild.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  $w = [int]$rb.Groups[3].Value; $h = [int]$rb.Groups[4].Value

  # ---- 首页网格：几何 + 长按弹菜单 ----
  Check (AssertCar "boot" $factor) "($dpi) 首页网格可点控件 >= 88dp"
  $dlg = LongPressOpen $w $h "标记为无效" "home_dialog"
  Check ($dlg -ne $null) "($dpi) 需求3: 首页网格长按弹出管理菜单"
  if ($dlg) { Check (AssertCar "home_dialog" $factor) "($dpi) 首页弹层对话框 >= 88dp"; & $adb shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 600 }

  # ---- 进设置 -> 节目源管理 ----
  Tap (Dump "home2") "设置" | Out-Null
  $found = $false; $sd = $null
  for ($i = 0; $i -lt 6; $i++) { if ($found) { break }; $sd = Dump "sett$i"; if (Has $sd "节目源管理") { $found = $true } else { & $adb shell input swipe ([int]($w*0.5)) ([int]($h*0.72)) ([int]($w*0.5)) ([int]($h*0.30)) 500; Start-Sleep -Milliseconds 700 } }
  Check $found "($dpi) 设置页找到节目源管理入口"
  if (-not $found) { continue }
  Tap $sd "节目源管理" | Out-Null
  $d = Dump "manage"
  Check (Has $d "检测全部") "($dpi) 已进入节目源管理页"

  # ---- 管理页：几何 + 搜索过滤 + 长按弹菜单 ----
  Check (AssertCar "manage" $factor) "($dpi) 管理页列表可点控件 >= 88dp"
  $sn = FindNode $d "搜索电台名称、频率、标签"; if (-not $sn) { $sn = (Nodes $d | Where-Object { $_.GetAttribute("text") -match "搜索电台" } | Select-Object -First 1) }
  Check ($sn -ne $null) "($dpi) 需求1: 管理页有搜索框"
  if ($sn) {
    $c = Center $sn; & $adb shell input tap ([int]$c.x) ([int]$c.y); Start-Sleep -Milliseconds 700
    & $adb shell input text "zzqqxx9999"; Start-Sleep -Milliseconds 900
    Check (HasTextLike (Dump "mfilter") "没有匹配") "($dpi) 需求1: 搜索无匹配给出空态"
    1..12 | ForEach-Object { & $adb shell input keyevent KEYCODE_DEL }
    & $adb shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 700
    Check (-not (HasTextLike (Dump "mclear") "没有匹配")) "($dpi) 需求1: 清空后列表恢复"
  }
  $dlg2 = LongPressOpen $w $h "检测连接" "manage_dialog"
  Check ($dlg2 -ne $null) "($dpi) 需求2: 管理页长按弹出管理菜单(检测连接)"
  if ($dlg2) { Check (AssertCar "manage_dialog" $factor) "($dpi) 管理弹层对话框 >= 88dp"; & $adb shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 600 }
}

& $adb shell wm density reset | Out-Null
& $adb shell settings put system user_rotation 0 | Out-Null
if ($fails -gt 0) { "RESULT: $fails 项失败（共执行 $ran 条断言）"; exit 1 }
if ($ran -lt 14) { "RESULT: 仅执行了 $ran 条断言(<14)，有断言被跳过，不算通过"; exit 1 }
"RESULT: ALL PASS（$ran 条断言全部执行）"; exit 0
