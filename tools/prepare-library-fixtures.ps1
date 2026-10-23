param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$DeviceSerial = '3871a9d6',
    [string]$SamplePath = (Join-Path $PSScriptRoot '..\app\build\device-test\video-fixtures\h264-24.mp4')
)
$ErrorActionPreference = 'Stop'
$sample = (Resolve-Path -LiteralPath $SamplePath).Path
& $AdbPath -s $DeviceSerial shell mkdir -p /sdcard/Movies/VAP-Library-Auto-Test/Many /sdcard/Movies/VAP-Library-Auto-Test/One
if ($LASTEXITCODE -ne 0) { throw 'Cannot create library fixture directories' }
$names = @('Many/vap_library_test_many_1.mp4', 'Many/vap_library_test_many_2.mp4', 'Many/vap_library_test_many_3.mp4', 'One/vap_library_test_one.mp4')
foreach ($name in $names) {
    $target = "/sdcard/Movies/VAP-Library-Auto-Test/$name"
    & $AdbPath -s $DeviceSerial push $sample $target | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot push $name" }
    & $AdbPath -s $DeviceSerial shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$target" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot index $name" }
}
Write-Output 'Prepared public synthetic video folders: Many (3), One (1). No user media is deleted.'
