$ErrorActionPreference = "Stop"
$base = "https://fm-api-prod.zhangmenglong.cn"
$srcFile = "e:\QoderWork\chinese-online-radio\tools\fm_all_programs.jsonl"
$outFile = "e:\QoderWork\chinese-online-radio\tools\fm_decrypted.jsonl"
$KEY = [System.Text.Encoding]::UTF8.GetBytes("Wt3FVt8zOrEIbavKzw9cYavg3O8cvm4D")

function Decrypt-Resource([string]$outerB64) {
    $d1 = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($outerB64))
    $head = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($d1.Substring(0,22) + "=="))
    $ct = [Convert]::FromBase64String($d1.Substring(22))
    $iv = [System.Text.Encoding]::UTF8.GetBytes($head)
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256; $aes.Key = $KEY; $aes.IV = $iv
    $aes.Mode = [System.Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7
    $dec = $aes.CreateDecryptor()
    $out = $dec.TransformFinalBlock($ct, 0, $ct.Length)
    $aes.Dispose()
    return [System.Text.Encoding]::UTF8.GetString($out)
}

# resume support
$done = @{}
if (Test-Path $outFile) {
    foreach ($line in Get-Content $outFile -Encoding utf8) {
        if ($line) {
            $o = $line | ConvertFrom-Json
            $done[$o.programId] = $true
        }
    }
}
"already done: $($done.Count)"

$lines = Get-Content $srcFile -Encoding utf8 | Where-Object { $_ }
"total programs: $($lines.Count)"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$batch = 0
foreach ($line in $lines) {
    $item = $line | ConvertFrom-Json
    if ($done.ContainsKey($item.programId)) { continue }
    $result = ""
    try {
        $r = Invoke-WebRequest -Uri "$base/app/fmProgram/resources/$($item.programId)" -TimeoutSec 20 -UseBasicParsing
        $j = $r.Content | ConvertFrom-Json
        if ($j.data -and $j.data.Count -gt 0) {
            $result = Decrypt-Resource $j.data[0]
        }
    } catch {
        $result = "ERROR: $($_.Exception.Message)"
    }
    $rec = [ordered]@{
        programId = $item.programId
        name = $item.programTitle
        category = $item.categoryName
        poster = $item.programPoster
        source = $item.programSource
        decrypted = $result
    }
    ($rec | ConvertTo-Json -Compress) | Out-File $outFile -Append -Encoding utf8
    $batch++
    if ($batch % 100 -eq 0) {
        $rate = [Math]::Round($batch / $sw.Elapsed.TotalSeconds, 1)
        Write-Host "progress $batch, elapsed $([int]$sw.Elapsed.TotalSeconds)s, rate $rate/s"
    }
    Start-Sleep -Milliseconds 120
}
"batch finished: $batch in $([int]$sw.Elapsed.TotalSeconds)s"
