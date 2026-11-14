$ErrorActionPreference = "Stop"
$jsonPath = "e:\QoderWork\chinese-online-radio\app\src\main\assets\radio_stations.json"
$decPath = "e:\QoderWork\chinese-online-radio\tools\fm_decrypted.jsonl"
$nl = "`r`n"

# --- load existing ---
$raw = [System.IO.File]::ReadAllText($jsonPath)
$existing = $raw | ConvertFrom-Json
$existingStations = $existing.stations
"existing: $($existingStations.Count)"

$existUrls = @{}
$existNames = @{}
foreach ($s in $existingStations) {
    $existUrls[$s.streamUrl] = $true
    $existNames[$s.name] = $true
    if ($s.sources) { foreach ($src in $s.sources) { $existUrls[$src.url] = $true } }
}

# --- filter decrypted open-type ---
$lines = Get-Content $decPath -Encoding utf8 | Where-Object { $_ }
$cands = @()
$dupUrl = 0; $dupName = 0; $badSrc = 0
$seenNewUrl = @{}
$seenNewName = @{}
foreach ($l in $lines) {
    $o = $l | ConvertFrom-Json
    try { $d = $o.decrypted | ConvertFrom-Json } catch { $badSrc++; continue }
    if ($d.source -ne "open" -or -not $d.url) { $badSrc++; continue }
    $u = $d.url.Trim()
    if ($existUrls.ContainsKey($u) -or $seenNewUrl.ContainsKey($u)) { $dupUrl++; continue }
    if ($existNames.ContainsKey($o.name) -or $seenNewName.ContainsKey($o.name)) { $dupName++; continue }
    $seenNewUrl[$u] = $true
    $seenNewName[$o.name] = $true
    $cands += [pscustomobject]@{ programId=$o.programId; name=$o.name; url=$u; categoryName=$o.categoryName; introduce=$o.source }
}
"candidates after dedup: $($cands.Count) (dupUrl: $dupUrl, dupName: $dupName, non-open: $badSrc)"

# --- category mapping by name keywords ---
function Map-Category([string]$n) {
    if ($n -match '交通') { return "traffic" }
    if ($n -match '新闻') { return "news" }
    if ($n -match '音乐') { return "music" }
    if ($n -match '戏曲|戏剧|曲艺') { return "opera" }
    if ($n -match '财经|经济') { return "finance" }
    if ($n -match '文艺|文学|评书|小说|故事') { return "arts" }
    if ($n -match '体育') { return "sports" }
    return "general"
}

function Esc([string]$s) {
    $s = $s.Replace('\', '\\').Replace('"', '\"')
    $s = $s.Replace("`t", '\t').Replace("`r", '\r').Replace("`n", '\n')
    return $s
}

$newEntries = New-Object System.Collections.Generic.List[string]
foreach ($c in $cands) {
    $cat = Map-Category $c.name
    $desc = ""
    if ($c.introduce -and $c.introduce -ne $c.name -and $c.introduce.Length -ge 2) { $desc = $c.introduce }
    $id = "ssf_$($c.programId)"
    $j = ''
    $j += '    {' + $nl
    $j += '      "id": "' + $id + '",' + $nl
    $j += '      "name": "' + (Esc $c.name) + '",' + $nl
    $j += '      "category": "' + $cat + '",' + $nl
    $j += '      "streamUrl": "' + (Esc $c.url) + '",' + $nl
    $j += '      "logoUrl": "",' + $nl
    $j += '      "frequency": "",' + $nl
    $j += '      "description": "' + (Esc $desc) + '",' + $nl
    $j += '      "website": "",' + $nl
    $j += '      "sources": [' + $nl
    $j += '        {' + $nl
    $j += '          "url": "' + (Esc $c.url) + '",' + $nl
    $j += '          "label": "随身FM",' + $nl
    $j += '          "bitrate": 0' + $nl
    $j += '        }' + $nl
    $j += '      ],' + $nl
    $j += '      "tags": [],' + $nl
    $j += '      "country": "中国",' + $nl
    $j += '      "language": "华语"' + $nl
    $j += '    }'
    $newEntries.Add($j)
}

# --- splice into raw text: last '    }' before '  ]' ---
$tailPattern = '    }' + $nl + '  ]' + $nl + '}'
$idx = $raw.LastIndexOf($tailPattern)
if ($idx -lt 0) { throw "tail pattern not found" }
$replacement = '    },' + $nl + ($newEntries -join (',' + $nl)) + $nl + '  ]' + $nl + '}'
$newRaw = $raw.Substring(0, $idx) + $replacement + $raw.Substring($idx + $tailPattern.Length)

# backup then write
Copy-Item $jsonPath "$jsonPath.apkmerge.bak" -Force
[System.IO.File]::WriteAllText($jsonPath, $newRaw, (New-Object System.Text.UTF8Encoding($false)))

# --- verify ---
$verify = [System.IO.File]::ReadAllText($jsonPath) | ConvertFrom-Json
"verified stations count: $($verify.stations.Count)"
$ids = $verify.stations | ForEach-Object { $_.id }
"duplicate ids: $((($ids | Group-Object | Where-Object { $_.Count -gt 1 })).Count)"
$verify.stations | Group-Object category | Select-Object Name, Count | Sort-Object Count -Descending | Format-Table -AutoSize
