param([switch]$SkipCar, [string]$Pkg = "com.radio.chinese.debug")
# 音频库播放页验收：回归基线（迷你条控件不缩水）+ 入口跳转 + 新页控件 + 歌词 + 车机档 88dp。
# 前提：设备在线；/sdcard/Music/opera_test/ 里有 demo.m4a(+demo.lrc，可选 demo.jpg 验双页态)。
# 纪律1：开头清旧 opera_*.xml——本项目旧坑是拿旧产物冒充本次抓取。
# 纪律2：Dump 每次实时 uiautomator dump，绝不复用文件判断两个时刻的状态。
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# 注意：debug 构建的 applicationId 带 .debug 后缀（与设备上可能残留的 release 包并存），
# 写死正式包名会调到旧 release 版——本项目实测踩过的假验收坑。release 验收传 -Pkg com.radio.chinese。
$pkg = $Pkg
$out = Join-Path (Split-Path $PSScriptRoot -Parent) ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$fails = 0
$ran = 0
# 每条 Check 计入分母；结尾校验执行数下限，防“断言被 if 跳过也报 ALL PASS”（终审 I4）
function Check($cond, [string]$what) { $script:ran++; if ($cond) { "PASS: $what" } else { "FAIL: $what"; $script:fails++ } }
function Dump([string]$name) {
  # 切密度/转屏瞬间 uiautomator 会回 null root 不落盘：重试直到拿到非空树，防“旧文件冒充本次抓取”
  for ($i = 1; $i -le 8; $i++) {
    & $adb shell rm /sdcard/ui.xml 2>$null | Out-Null
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    if ((& $adb shell ls /sdcard/ui.xml 2>$null)) {
      Remove-Item "$out\opera_$name.xml" -ErrorAction SilentlyContinue
      & $adb pull /sdcard/ui.xml "$out\opera_$name.xml" | Out-Null
      $f = "$out\opera_$name.xml"
      if ((Test-Path $f) -and ((Get-Item $f).Length -gt 500)) {
        try { return [xml](Get-Content $f -Raw -Encoding UTF8) } catch { }
      }
    }
    Start-Sleep -Milliseconds 1500
  }
  Write-Host "  (Dump $name 连续 8 次拿不到有效树)"
  return [xml]'<?xml version="1.0" ?><hierarchy rotation="0"></hierarchy>'
}
function FindNode($doc, [string]$label) {
  @($doc.SelectNodes("//*")) | Where-Object {
    $_.GetAttribute("content-desc") -eq $label -or $_.GetAttribute("text") -eq $label
  } | Select-Object -First 1
}
function Has($doc, [string]$label) { [bool](FindNode $doc $label) }
function HasTextLike($doc, [string]$pattern) {
  [bool]((@($doc.SelectNodes("//*")) | Where-Object { $_.GetAttribute("text") -match $pattern } | Select-Object -First 1))
}
function Tap($doc, [string]$label) {
  $n = FindNode $doc $label
  if (-not $n) { Write-Host "  (找不到 $label)"; return $false }
  $m = [regex]::Match($n.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
  & $adb shell input tap (([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2) (([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
  Start-Sleep -Milliseconds 900
  return $true
}
Remove-Item "$out\opera_*.xml" -ErrorAction SilentlyContinue

# --- 环境复位：上一轮可能把设备留在横屏/改过密度，手机档必须在默认竖屏下跑 ---
& $adb shell wm density reset | Out-Null
& $adb shell settings put system user_rotation 0 | Out-Null
Start-Sleep -Milliseconds 800

# --- 冷启动 + 就绪轮询 + 权限预授权 ---
& $adb shell pm grant $pkg android.permission.READ_MEDIA_AUDIO 2>$null | Out-Null
& $adb shell am force-stop $pkg
& $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
$ready = $false
1..25 | ForEach-Object { if (-not $ready) { Start-Sleep -Seconds 1; if ((Has (Dump "boot") "音频库")) { $ready = $true } } }
if (-not $ready) { "FAIL: 冷启动 25 秒未就绪"; exit 2 }

# --- 用本地夹具起播：本地 Tab 根屏先列文件夹再列文件，必须多点一跳 ---
if (-not (& $adb shell ls /sdcard/Music/opera_test/demo.m4a 2>$null)) { "FAIL: 缺夹具 /sdcard/Music/opera_test/demo.m4a"; exit 3 }
& $adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/opera_test/demo.m4a" | Out-Null
Tap (Dump "home") "音频库" | Out-Null
Tap (Dump "audio") "本地" | Out-Null
$d = Dump "beforeplay"
Check (-not (Has $d "停止播放")) "起播前无播放条"
if (-not (Tap $d "Music/opera_test (1)")) { "FAIL: 本地根屏没找到 opera_test 文件夹（媒体库未索引到？重启 MediaProvider 或改真机裁决）"; exit 3 }
$d = Dump "filelist"
if (-not (Tap $d "demo.m4a")) { "FAIL: 文件夹里没有 demo.m4a"; exit 3 }

# --- 回归基线：迷你条控件一个不减 ---
Start-Sleep -Seconds 2
$d = Dump "minibar"
Check (Has $d "播放进度") "迷你条: 滑条存在"
Check (Has $d "上一段") "迷你条: 上一段"
Check (Has $d "下一段") "迷你条: 下一段"
Check ((Has $d "暂停播放") -or (Has $d "继续播放")) "迷你条: 播放/暂停"
Check (Has $d "停止播放") "迷你条: 停止"
Check ((Has $d "列表循环") -or (Has $d "单曲循环") -or (Has $d "随机播放")) "迷你条: 循环模式"
Check (Has $d "demo.m4a") "迷你条: 标题信息行"
# 点暂停不许跳页（新 onClick 不得吞既有按钮，Review Focus 5）
$d = Dump "pause_state"
$pl = FindNode $d "暂停播放"; if (-not $pl) { $pl = FindNode $d "继续播放" }
if ($pl) { Tap $d $pl.GetAttribute("content-desc") | Out-Null } else { Write-Host "  (无暂停按钮)" }
$d2 = Dump "pause_after"
Check ((Has $d2 "停止播放") -and -not (Has $d2 "正在播放")) "点暂停仍留在音频库页（新 onClick 未吞按钮）"

# --- 进入全屏页 ---
Tap $d2 "展开全屏播放" | Out-Null
$d = Dump "player"
Check (Has $d "正在播放") "新页: 顶栏标题"
Check (Has $d "播放进度") "新页: 滑条"
Check ((Has $d "上一段") -and (Has $d "下一段")) "新页: 上/下一段"
Check ((Has $d "暂停播放") -or (Has $d "继续播放")) "新页: 播放/暂停"
Check (Has $d "停止播放") "新页: 停止"
Check ((Has $d "后退 10 秒") -and (Has $d "前进 10 秒")) "新页: ±10 秒（有时长）"
# 歌词真阳性：本地同名 .lrc 必须可见（夹具自带 lrc，看不到就是链路断，不给“占位也算过”的后门）
Check (Has $d "第一句 测试歌词") "新页: 歌词真阳性(本地 .lrc 加载成功)"
if ((Has $d "专辑封面") -and (Has $d "第一句 测试歌词")) {
  # 双页态：横滑切页后歌词可见
  $scr = ((& $adb shell wm size) -join " "); $m = [regex]::Match($scr, '(\d+)x(\d+)')
  $w = [int]$m.Groups[1].Value; $h = [int]$m.Groups[2].Value
  & $adb shell input swipe ([int]($w*0.7)) ([int]($h*0.4)) ([int]($w*0.3)) ([int]($h*0.4)) 400
  Start-Sleep -Milliseconds 900
  Check (Has (Dump "swiped") "第一句 测试歌词") "新页: 封面→歌词滑动切换"
}
# 点歌词行跳播：seek 真生效的硬证据 = 时间文本落到被点行的时间戳（00:08，给 ±3s 容差）
$d3 = Dump "lyrictap"
if (Tap $d3 "第二句 点我应该跳转") {
  $d4 = Dump "lyrictap_after"
  $t = (@($d4.SelectNodes("//*")) | Where-Object { $_.GetAttribute("text") -match '^\d+:\d\d' } | Select-Object -First 1)
  $txt = if ($t) { $t.GetAttribute("text") } else { "" }
  Check ($txt -match '^0:0[5-9]') "新页: 点歌词行 seek 到 0:08(实测 $txt)"
} else {
  Check $false "新页: 歌词行不可点(点击入口丢失)"
}
# 返回不中断播放
Tap (Dump "backfrom") "返回" | Out-Null
$d = Dump "back"
Check (Has $d "停止播放") "返回后迷你条仍在（播放未中断）"
# 停止 = 回列表并清条：重新进全屏页再点停止
Tap (Dump "relaunch") "展开全屏播放" | Out-Null
Tap (Dump "player2") "停止播放" | Out-Null
Start-Sleep -Seconds 1
$d = Dump "stopped"
Check (-not (Has $d "停止播放")) "新页停止: 自动返回列表且播放条消失"

# --- 车机档：横屏 160/240 两档 88dp ---
if (-not $SkipCar) {
  foreach ($dpi in 160, 240) {
    & (Join-Path $PSScriptRoot "car_env.ps1") -dpi $dpi | Out-Null
    & $adb shell am force-stop $pkg; & $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
    # 切密度后冷启动比平时慢：轮询到“音频库”可见再继续，不等固定秒数
    $ok = $false
    1..20 | ForEach-Object { if (-not $ok) { Start-Sleep -Seconds 2; if (Has (Dump "carboot") "音频库") { $ok = $true } } }
    if (-not $ok) { "FAIL: 车机档($dpi) 启动 40 秒未就绪"; $script:fails++; continue }
    # 硬校验真横屏：切密度会打断 user_rotation，竖屏下 auto 判成 Phone 档，
    # 拿手机尺寸去评 88dp 会制造整批假违规（本项目实测踩过的假阳性）
    $land = $false
    1..5 | ForEach-Object {
      if (-not $land) {
        $r = [regex]::Match((Dump "carorient").DocumentElement.FirstChild.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if ([int]$r.Groups[3].Value -gt [int]$r.Groups[4].Value) { $land = $true }
        else { & $adb shell settings put system user_rotation 1 | Out-Null; Start-Sleep -Seconds 2 }
      }
    }
    if (-not $land) { "FAIL: 车机档($dpi) 无法进入横屏，跳过断言不给假通过"; $script:fails++; continue }
    Tap (Dump "carhome") "音频库" | Out-Null
    Tap (Dump "caraudio") "本地" | Out-Null
    Tap (Dump "carlist0") "Music/opera_test (1)" | Out-Null
    Tap (Dump "carlist") "demo.m4a" | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Tap (Dump "carminibar") "展开全屏播放")) { "FAIL: 车机档($dpi) 无展开入口"; $script:fails++; continue }
    $a = & (Join-Path $PSScriptRoot "car_assert.ps1") -name ("carplayer_" + $dpi) -density ($dpi / 160.0) -minDp 88.0
    $a | ForEach-Object { "  [car$dpi] $_" }
    Check (($a | Out-String) -notmatch '违规') "车机档($dpi): 播放页可点控件 >= 88dp"
    & $adb shell wm density reset | Out-Null
    & $adb shell settings put system user_rotation 0 | Out-Null
  }
}
if ($fails -gt 0) { "RESULT: $fails 项失败（共执行 $ran 条断言）"; exit 1 }
if ($ran -lt 21) { "RESULT: 仅执行了 $ran 条断言(<21)，有断言被跳过，不算通过"; exit 1 }
"RESULT: ALL PASS（$ran 条断言全部执行）"; exit 0
