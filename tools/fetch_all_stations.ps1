$ErrorActionPreference = "Stop"
$base = "https://fm-api-prod.zhangmenglong.cn"
$outFile = "e:\QoderWork\chinese-online-radio\tools\fm_all_programs.jsonl"

# load categories, find children of the China-region parent
$catsRaw = Get-Content "e:\QoderWork\chinese-online-radio\tools\fm_categories_raw.json" -Raw | ConvertFrom-Json
$chinaParent = "1140276664295624704"
$chinaCats = $catsRaw.data | Where-Object { $_.categoryParentId -eq $chinaParent }
"China region categories: $($chinaCats.Count)"

$all = @{}
foreach ($cat in $chinaCats) {
    $cid = $cat.categoryId
    $cname = $cat.categoryName
    $page = 1
    $pageSize = 10
    $emptyStreak = 0
    while ($true) {
        try {
            $r = Invoke-WebRequest -Uri "$base/app/fmProgram/search?categoryIds=$cid&pageNum=$page&pageSize=$pageSize" -TimeoutSec 20 -UseBasicParsing
            $j = $r.Content | ConvertFrom-Json
        } catch {
            "fetch error cat=$cname page=$page : $($_.Exception.Message)"
            break
        }
        if (-not $j.data -or $j.data.Count -eq 0) { break }
        $new = 0
        foreach ($item in $j.data) {
            if (-not $all.ContainsKey($item.programId)) {
                $all[$item.programId] = $item | Add-Member -NotePropertyName categoryName -NotePropertyValue $cname -PassThru
                $new++
            }
        }
        if ($new -eq 0) { break }
        $page++
        if ($page -gt 80) { "page limit hit for $cname"; break }
        Start-Sleep -Milliseconds 150
    }
    "cat $cname done, total unique so far: $($all.Count)"
}

"TOTAL programs collected: $($all.Count)"
$all.Values | ForEach-Object { $_ | ConvertTo-Json -Compress } | Out-File $outFile -Encoding utf8
$radio = $all.Values | Where-Object { $_.programType -eq "1" }
"TOTAL radio (type=1): $($radio.Count)"
