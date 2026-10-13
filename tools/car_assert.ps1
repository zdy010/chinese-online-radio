param(
  [string]$name = "cur",
  [double]$density = 0,
  [double]$minDp = 48.0,
  [switch]$InFile
)
# 可点控件命中区断言：车机档要求 >= 88dp，手机档基线要求 >= 48dp。
# 先 dump 当前界面，再按 density 把 px 换算成 dp，短边不足阈值即记为违规。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
if ($density -le 0) {
  # 不显式传 density 时从设备读：必须优先取 Override，`wm density` 先输出的是 Physical
  $d = ((& $adb shell wm density) -join " ")
  $ov = [regex]::Match($d, 'Override density: (\d+)')
  $ph = [regex]::Match($d, 'Physical density: (\d+)')
  $dpi = if ($ov.Success) { [int]$ov.Groups[1].Value }
    elseif ($ph.Success) { [int]$ph.Groups[1].Value }
    else { 160 }
  $density = $dpi / 160.0
}
# dump 写到独立的 _assert 文件：以前直接写 <name>.xml，会把 cap.ps1 采的基线覆盖掉
$src = "$out\$name.xml"
if (-not $InFile) {
  & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb pull /sdcard/ui.xml "$out\${name}_assert.xml" | Out-Null
  $src = "$out\${name}_assert.xml"
}
if (-not (Test-Path $src)) { "ERROR: 找不到 $src（先用 cap.ps1 采集，或去掉 -InFile）"; exit 2 }
[xml]$doc = Get-Content $src -Raw -Encoding UTF8

# 先取屏幕根节点边界：下面判断“是否被裁切”要用。
# uiautomator 对被部分裁切的节点上报的是**剩余可见部分**的 bounds，
# 于是一个 88dp 的 chip 会读成 21dp。拿这种残缺尺寸判命中区违规是假阳性，
# 本项目就因此误报过一个并不存在的“车机对话框 Dimens 失效”缺陷。
#
# 注意这里的参照物只能到**窗口边界**：整页滚动时控件被屏幕底边裁到，这里能挑出来；
# 而对话框/内层滚动容器把自己的子控件裁短时，切割线在窗口内部（实测在 y=296 与
# y=2107/2163），这里抓不到。那种情况必须多帧滚动重采：同一个控件在某一帧
# 量到 >= 阈值即说明它本来的尺寸达标，稳定偏矮才是真缺陷。
$root = $doc.DocumentElement.FirstChild
$rm = [regex]::Match($root.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
$scrR = 999999; $scrB = 999999
if ($rm.Success) { $scrR = [int]$rm.Groups[3].Value; $scrB = [int]$rm.Groups[4].Value }

$viol = New-Object System.Collections.Generic.List[string]
$clipped = New-Object System.Collections.Generic.List[string]
function Walk($nodes) {
  foreach ($c in $nodes) {
    if ($c.GetAttribute("clickable") -eq "true") {
      $m = [regex]::Match($c.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
      if ($m.Success) {
        $w = ([int]$m.Groups[3].Value - [int]$m.Groups[1].Value) / $density
        $h = ([int]$m.Groups[4].Value - [int]$m.Groups[2].Value) / $density
        # 小于 1dp 的节点是被裁切或不可见，不计入
        if ($w -ge 1 -and $h -ge 1 -and ($w -lt $minDp -or $h -lt $minDp)) {
          $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
          $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
          $lab = $c.GetAttribute("content-desc")
          if (-not $lab) { $lab = $c.GetAttribute("text") }
          # 边缘接触 = 尺寸不可采信，单独报，不混进违规清单
          if ($x1 -lt 0 -or $y1 -lt 0 -or $x2 -ge $scrR - 2 -or $y2 -ge $scrB - 2) {
            $clipped.Add(("{0}x{1}dp '{2}' {3}" -f [math]::Round($w, 1), [math]::Round($h, 1), $lab, $m.Value))
          } else {
            $viol.Add(("{0}x{1}dp '{2}' {3}" -f [math]::Round($w, 1), [math]::Round($h, 1), $lab, $m.Value))
          }
        }
      }
    }
    if ($c.FirstChild) { Walk $c.ChildNodes }
  }
}
Walk $doc.DocumentElement.ChildNodes
if ($viol.Count -eq 0) {
  "OK: 所有可点控件命中区 >= $minDp dp (density $density)"
} else {
  "VIOLATIONS ($($viol.Count)) 阈值 $minDp dp @ density $density"
  $viol | ForEach-Object { "  $_" }
}
if ($clipped.Count -gt 0) {
  # 这些节点只量到了被裁切后的可见部分，不能得出达标与否的结论：滚动后再采一帧才算
  "UNCERTAIN ($($clipped.Count)) 贴窗口边被裁切、尺寸不可采信（需滚动后重采）"
  $clipped | ForEach-Object { "  $_" }
}
if ($viol.Count -gt 0) {
  # 单帧无法区分“真矮”与“被内层容器裁短”。不排掉这一层就会再次把测量问题当产品缺陷。
  "提示：以上违规仍可能包含被对话框/滚动容器裁切的节点。"
  "请沿同一控件多滚几屏重采：只要有一帧达到阈值，就是裁切而非违规。"
}

# 第 2 项断言：可点控件必须落在屏幕内。被裁到屏外的节点在 dump 里仍然带完整尺寸，
# 只看尺寸会漏判“rail 第四项装不下”这类错。
$root = $doc.DocumentElement.FirstChild
$rm = [regex]::Match($root.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
if ($rm.Success) {
  $rr = [int]$rm.Groups[3].Value; $rb = [int]$rm.Groups[4].Value
  $off = New-Object System.Collections.Generic.List[string]
  function WalkOff($nodes) {
    foreach ($c in $nodes) {
      if ($c.GetAttribute("clickable") -eq "true") {
        $m = [regex]::Match($c.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if ($m.Success -and ([int]$m.Groups[3].Value -gt $rr + 2 -or [int]$m.Groups[4].Value -gt $rb + 2)) {
          $lab = $c.GetAttribute("content-desc"); if (-not $lab) { $lab = $c.GetAttribute("text") }
          $off.Add("'$lab' $($m.Value) 超出可视区 [$rr,$rb]")
        }
      }
      if ($c.FirstChild) { WalkOff $c.ChildNodes }
    }
  }
  WalkOff $doc.DocumentElement.ChildNodes
  if ($off.Count -eq 0) { "OK: 可点控件均在可视区内" }
  else { "OFFSCREEN ($($off.Count))"; $off | ForEach-Object { "  $_" } }
}
