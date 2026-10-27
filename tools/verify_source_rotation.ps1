$sdk = "C:\Users\zdy01\AppData\Local\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$pkg = "com.radio.chinese.debug"

& $adb shell am force-stop $pkg
& $adb shell svc wifi disable
& $adb shell svc data disable
Start-Sleep -Seconds 3
& $adb logcat -c
& $adb shell am start -n "$pkg/com.radio.chinese.MainActivity" | Out-Null
Start-Sleep -Seconds 8
# 点列表第一行（540,942 是当前字体档下首行的中心）
& $adb shell input tap 540 942
Start-Sleep -Seconds 75

$log = (& $adb logcat -d 2>&1) | Out-String
$err = ($log -split "`n" | Select-String -Pattern "Playback error|UnknownHost|state=ERROR" ).Count
$prep = ($log -split "`n" | Select-String -Pattern "MediaSessionService: onSessionPlaybackStateChanged").Count
$states = ($log -split "`n" | Select-String -Pattern "playbackState=PlaybackState \{state=([A-Z]+)" | ForEach-Object {
    [regex]::Match($_.ToString(), "state=([A-Z]+)").Groups[1].Value
})
Write-Host "播放器报错行数: $err"
Write-Host "会话状态变更条数: $prep"
Write-Host "`n状态序列（去重后按出现顺序）:"
$prev = ""
foreach ($s in $states) { if ($s -ne $prev) { Write-Host "  -> $s"; $prev = $s } }
Write-Host "`n关键错误行:"
($log -split "`n" | Select-String -Pattern "Playback error|UnknownHostException|Caused by" | Select-Object -First 12) -join "`n"

& $adb shell svc wifi enable
& $adb shell svc data enable
Start-Sleep -Seconds 4
& $adb shell cmd wifi connect-network AndroidWifi open | Out-Null
Write-Host "`nnetwork restored"
