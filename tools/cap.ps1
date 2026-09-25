param([string]$name = "shot")
# 截图 + uiautomator dump + 可读元素清单。产物落在仓库根的 .qtest/（已 gitignore）。
$ErrorActionPreference = "SilentlyContinue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) ".qtest"
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $adb shell screencap -p /sdcard/shot.png | Out-Null
& $adb pull /sdcard/shot.png "$out\$name.png" | Out-Null
& $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
& $adb pull /sdcard/ui.xml "$out\$name.xml" | Out-Null
[xml]$doc = Get-Content "$out\$name.xml" -Raw -Encoding UTF8
$lines = New-Object System.Collections.Generic.List[string]
function Walk($nodes, $depth) {
  foreach ($c in $nodes) {
    $t = $c.GetAttribute("text")
    $d = $c.GetAttribute("content-desc")
    $b = $c.GetAttribute("bounds")
    $fl = ""
    if ($c.GetAttribute("clickable") -eq "true") { $fl += "C" }
    if ($c.GetAttribute("long-clickable") -eq "true") { $fl += "L" }
    if ($c.GetAttribute("scrollable") -eq "true") { $fl += "S" }
    if ($t -or $d) {
      $cls = $c.GetAttribute("class") -replace "android\.(widget|view)\.", ""
      $lines.Add(("[{0,2}] {1,-3} {2,-18} {3,-26} '{4}' '{5}'" -f $depth, $fl, $cls, $b, $t, $d))
    }
    if ($c.FirstChild) { Walk $c.ChildNodes ($depth + 1) }
  }
}
Walk $doc.DocumentElement.ChildNodes 0
Set-Content -Path "$out\$name.txt" -Value $lines -Encoding UTF8
Write-Output "captured $name -> $($lines.Count) nodes"
