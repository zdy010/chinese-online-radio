param([string]$Pkg = "com.radio.chinese.debug")
# 本次三项改动的 UI 回归：
#   1) 节目源管理页搜索框存在且能过滤列表
#   2) 节目源管理页长按电台项弹出管理菜单
#   3) 电台节目列表页长按电台项弹出管理菜单
# 纪律同 opera_player_check.ps1：先清旧产物、Dump 实时抓取、断言计数下限防“被跳过也报 PASS”。
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = $Pkg
$out = Join-Path (Split-Path $PSScriptRoot -Parent) ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$fails = 0
$ran = 0
function Check($cond, [string]$what) { $script:ran++; if ($cond) { "PASS: $what" } else { "FAIL: $what"; $script:fails++ } }
function Dump([string]$name) {
  for ($i = 1; $i -le 8; $i++) {
    & $adb shell rm /sdcard/ui.xml 2>$null | Out-Null
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    if ((& $adb shell ls /sdcard/ui.xml 2>$null)) {
      Remove-Item "$out\mm_$name.xml" -ErrorAction SilentlyContinue
      & $adb pull /sdcard/ui.xml "$out\mm_$name.xml" | Out-Null
      $f = "$out\mm_$name.xml"
      if ((Test-Path $f) -and ((Get-Item $f).Length -gt 500)) {
        try { return [xml](Get-Content $f -Raw -Encoding UTF8) } catch { }
      }
    }
    Start-Sleep -Milliseconds 1200
  }
  return [xml]'<?xml version="1.0" ?><hierarchy rotation="0"></hierarchy>'
}
function Nodes($doc) { @($doc.SelectNodes("//*")) }
function FindNode($doc, [string]$label) {
  Nodes $doc | Where-Object { $_.GetAttribute("content-desc") -eq $label -or $_.GetAttribute("text") -eq $label } | Select-Object -First 1
}
function Has($doc, [string]$label) { [bool](FindNode $doc $label) }
function HasTextLike($doc, [string]$pattern) { [bool]((Nodes $doc) | Where-Object { $_.GetAttribute("text") -match $pattern } | Select-Object -First 1) }
function Center($node) {
  $m = [regex]::Match($node.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  @{ x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2; y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2 }
}
function Tap($doc, [string]$label) {
  $n = FindNode $doc $label; if (-not $n) { Write-Host "  (找不到 $label)"; return $false }
  $c = Center $n; & $adb shell input tap ([int]$c.x) ([int]$c.y); Start-Sleep -Milliseconds 900; return $true
}
function LongPressAt([int]$x, [int]$y, [int]$ms = 1000) { & $adb shell input swipe $x $y $x $y $ms; Start-Sleep -Milliseconds 800 }
# 在列表区从给定 y 起逐行长按，命中期望对话框标记即返回 true（不依赖死的行坐标）
function LongPressUntilDialog([int]$w, [int]$startY, [int]$stepY, [int]$tries, [string]$marker) {
  for ($i = 0; $i -lt $tries; $i++) {
    $y = $startY + $i * $stepY
    LongPressAt ([int]($w * 0.45)) $y
    if (Has (Dump "lp$i") $marker) { & $adb shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Milliseconds 700; return $true }
  }
  return $false
}

# --- 环境复位 ---
& $adb shell wm density reset | Out-Null
& $adb shell settings put system user_rotation 0 | Out-Null
& $adb shell settings put system accelerometer_rotation 0 | Out-Null
Start-Sleep -Milliseconds 800
Remove-Item "$out\mm_*.xml" -ErrorAction SilentlyContinue

# --- 冷启动 + 就绪轮询 ---
& $adb shell am force-stop $pkg
& $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
$ready = $false
1..25 | ForEach-Object { if (-not $ready) { Start-Sleep -Seconds 1; if (Has (Dump "boot") "音频库") { $ready = $true } } }
if (-not $ready) { "FAIL: 冷启动 25 秒未就绪"; exit 2 }
$scr = ((& $adb shell wm size) -join " "); $m = [regex]::Match($scr, '(\d+)x(\d+)')
$w = [int]$m.Groups[1].Value; $h = [int]$m.Groups[2].Value

# ===== 需求3：电台节目列表页长按弹管理菜单 =====
$d = Dump "home"
Check ((Has $d "分类") -or (Has $d "全部")) "电台页已加载列表"
# 首行电台大致在搜索栏+页签+分类条之下，逐行试长按直到“标记为无效”出现
$okHome = LongPressUntilDialog $w ([int]($h * 0.30)) ([int]($h * 0.13)) 4 "标记为无效"
Check $okHome "需求3: 电台列表长按弹出管理菜单(标记为无效)"

# ===== 进入设置 → 节目源管理 =====
Tap (Dump "home2") "设置" | Out-Null
# 节目源管理入口在设置页下方，需下滑才可见
$found = $false; $sd = $null
for ($i = 0; $i -lt 6; $i++) {
  if ($found) { break }
  $sd = Dump "sett$i"
  if (Has $sd "节目源管理") { $found = $true }
  else { & $adb shell input swipe ([int]($w * 0.5)) ([int]($h * 0.72)) ([int]($w * 0.5)) ([int]($h * 0.30)) 500; Start-Sleep -Milliseconds 700 }
}
if (-not $found) { "FAIL: 设置页未见 [节目源管理] 入口，后续跳过"; exit 3 }
Tap $sd "节目源管理" | Out-Null
$d = Dump "manage"
Check (Has $d "检测全部") "已进入节目源管理页"

# ===== 需求1: 搜索框存在 + 能过滤 =====
$hasSearch = (Has $d "搜索电台名称、频率、标签") -or (HasTextLike $d "搜索电台")
Check $hasSearch "需求1: 节目源管理页有搜索框"
$sn = FindNode $d "搜索电台名称、频率、标签"
if (-not $sn) { $sn = (@(Nodes $d) | Where-Object { $_.GetAttribute("text") -match "搜索电台" } | Select-Object -First 1) }
if ($sn) {
  $c = Center $sn; & $adb shell input tap ([int]$c.x) ([int]$c.y); Start-Sleep -Milliseconds 700
  & $adb shell input text "zzqqxx9999"; Start-Sleep -Milliseconds 900
  Check (HasTextLike (Dump "search_filtered") "没有匹配") "需求1: 搜索无匹配关键字时给出空态提示"
  1..12 | ForEach-Object { & $adb shell input keyevent KEYCODE_DEL }
  Start-Sleep -Milliseconds 800
  Check (-not (HasTextLike (Dump "search_cleared") "没有匹配")) "需求1: 清空关键字后列表恢复"
} else {
  Check $false "需求1: 无法定位搜索框"
}
# 收起软键盘，免得遮挡下方行（IME 下 BACK 只收键盘不会退页）
& $adb shell input keyevent KEYCODE_BACK | Out-Null
Start-Sleep -Milliseconds 700

# ===== 需求2: 节目源管理页长按弹管理菜单（含 [检测连接] 行） =====
$d = Dump "manage2"
Check (Has $d "检测全部") "长按前仍在节目源管理页"
$okManage = LongPressUntilDialog $w ([int]($h * 0.35)) ([int]($h * 0.13)) 4 "检测连接"
Check $okManage "需求2: 节目源管理页长按弹出管理菜单(检测连接)"

if ($fails -gt 0) { "RESULT: $fails 项失败（共执行 $ran 条断言）"; exit 1 }
if ($ran -lt 6) { "RESULT: 仅执行了 $ran 条断言(<6)，有断言被跳过，不算通过"; exit 1 }
"RESULT: ALL PASS（$ran 条断言全部执行）"; exit 0
