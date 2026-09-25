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
$viol = New-Object System.Collections.Generic.List[string]
function Walk($nodes) {
  foreach ($c in $nodes) {
    if ($c.GetAttribute("clickable") -eq "true") {
      $m = [regex]::Match($c.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
      if ($m.Success) {
        $w = ([int]$m.Groups[3].Value - [int]$m.Groups[1].Value) / $density
        $h = ([int]$m.Groups[4].Value - [int]$m.Groups[2].Value) / $density
        # 小于 1dp 的节点是被裁切或不可见，不计入
        if ($w -ge 1 -and $h -ge 1 -and ($w -lt $minDp -or $h -lt $minDp)) {
          $lab = $c.GetAttribute("content-desc")
          if (-not $lab) { $lab = $c.GetAttribute("text") }
          $viol.Add(("{0}x{1}dp '{2}' {3}" -f [math]::Round($w, 1), [math]::Round($h, 1), $lab, $m.Value))
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
