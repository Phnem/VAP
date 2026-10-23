param(
    [Parameter(Mandatory = $true)][string]$SamplePath,
    [string]$DeviceSerial = '3871a9d6',
    [switch]$KeepFixture,
    [ValidateSet('foss','cast')][string]$Distribution = 'foss',
    [ValidateSet('core', 'controls', 'ui', 'display', 'video', 'video-ui', 'audio', 'audio-ui', 'library', 'subtitles', 'resume', 'ass', 'online', 'network', 'protocols', 'servers', 'casting', 'whisper', 'sync', 'translation', 'all')][string]$Suite = 'core',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$suiteGuard = [System.Threading.Mutex]::new($false, "Local\VAP-Test-$DeviceSerial")
$guardTaken = $false
try {
    $guardTaken = $suiteGuard.WaitOne(60000)
    if (-not $guardTaken) { throw 'Another VAP device test is still using this phone' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$sample = (Resolve-Path -LiteralPath $SamplePath).Path
$appApk = Join-Path $projectRoot "app\build\outputs\apk\$Distribution\debug\app-$Distribution-debug.apk"
$testApk = Join-Path $projectRoot "app\build\outputs\apk\androidTest\$Distribution\debug\app-$Distribution-debug-androidTest.apk"
foreach ($apk in @($appApk, $testApk)) {
    & $AdbPath -s $DeviceSerial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $apk" }
}
& $AdbPath -s $DeviceSerial push $sample /data/local/tmp/vap-test.mp4
if ($LASTEXITCODE -ne 0) { throw 'Cannot push test sample' }
& $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files
& $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp /data/local/tmp/vap-test.mp4 files/vap-test.mp4
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare test sample' }
& $AdbPath -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP
if ($Suite -in @('video', 'video-ui', 'all')) {
    $fixtures = Join-Path $projectRoot 'app\build\device-test\video-fixtures'
    if (-not (Test-Path -LiteralPath $fixtures)) { throw 'Run tools/prepare-video-fixtures.ps1 first' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/video-fixtures
    foreach ($fixture in (Get-ChildItem -LiteralPath $fixtures -File)) {
        & $AdbPath -s $DeviceSerial push $fixture.FullName /data/local/tmp/vap-fixture | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot push $($fixture.Name)" }
        & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp /data/local/tmp/vap-fixture "files/video-fixtures/$($fixture.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Cannot copy $($fixture.Name)" }
    }
}
if ($Suite -eq 'library') {
    $fixture = Join-Path $projectRoot 'app\build\device-test\video-fixtures\h264-24.mp4'
    if (-not (Test-Path -LiteralPath $fixture)) { throw 'Run tools/prepare-video-fixtures.ps1 first' }
    & $AdbPath -s $DeviceSerial push $fixture /data/local/tmp/vap-library-sample.mp4 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot push library sample' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/video-fixtures
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp /data/local/tmp/vap-library-sample.mp4 files/video-fixtures/h264-24.mp4
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare library sample' }
}
if ($Suite -in @('audio', 'audio-ui', 'all')) {
    $fixtures = Join-Path $projectRoot 'app\build\device-test\audio-fixtures'
    if (-not (Test-Path -LiteralPath $fixtures)) { throw 'Run tools/prepare-audio-fixtures.ps1 first' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/audio-fixtures
    foreach ($fixture in (Get-ChildItem -LiteralPath $fixtures -File)) {
        & $AdbPath -s $DeviceSerial push $fixture.FullName /data/local/tmp/vap-audio-fixture | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot push $($fixture.Name)" }
        & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp /data/local/tmp/vap-audio-fixture "files/audio-fixtures/$($fixture.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Cannot copy $($fixture.Name)" }
    }
    if ($Suite -in @('audio-ui', 'all')) {
        & $AdbPath -s $DeviceSerial push (Join-Path $fixtures 'external.mka') /sdcard/Download/VAP-stage61-external.mka | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare public SAF audio fixture' }
    }
}
if ($Suite -in @('subtitles', 'ass', 'online', 'all')) {
    $fixtures = Join-Path $projectRoot 'app\build\device-test\subtitle-fixtures'
    if (-not (Test-Path -LiteralPath $fixtures)) { throw 'Run tools/prepare-subtitle-fixtures.ps1 first' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/subtitle-fixtures
    foreach ($fixture in (Get-ChildItem -LiteralPath $fixtures -File)) {
        & $AdbPath -s $DeviceSerial push $fixture.FullName /data/local/tmp/vap-subtitle-fixture | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot push $($fixture.Name)" }
        & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp /data/local/tmp/vap-subtitle-fixture "files/subtitle-fixtures/$($fixture.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Cannot copy $($fixture.Name)" }
    }
    & $AdbPath -s $DeviceSerial push (Join-Path $fixtures 'english.srt') /sdcard/Download/VAP-stage62.srt | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare public SAF subtitle fixture' }
    & $AdbPath -s $DeviceSerial shell mkdir -p /sdcard/Download/VAP-Subtitle-Test
    & $AdbPath -s $DeviceSerial push (Join-Path $fixtures 'embedded.mkv') /sdcard/Download/VAP-Subtitle-Test/embedded.mkv | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare SAF video fixture' }
    & $AdbPath -s $DeviceSerial push (Join-Path $fixtures 'russian.srt') /sdcard/Download/VAP-Subtitle-Test/embedded.ru.srt | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare sibling subtitle fixture' }
}
if ($Suite -in @('network', 'protocols', 'casting', 'whisper', 'sync', 'translation', 'all')) {
    $fixtures = Join-Path $projectRoot 'app\build\device-test\network-fixtures'
    if (-not (Test-Path -LiteralPath $fixtures)) { throw 'Run tools/prepare-network-fixtures.ps1 first' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/network-fixtures
    & $AdbPath -s $DeviceSerial push "$fixtures/." /data/local/tmp/vap-network-fixtures | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot push network fixtures' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp -R /data/local/tmp/vap-network-fixtures/. files/network-fixtures/
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy network fixtures' }
}
if ($Suite -eq 'protocols') {
    $serverState = Join-Path $projectRoot 'app\build\device-test\network-server\server.json'
    & $AdbPath -s $DeviceSerial push $serverState /data/local/tmp/vap-network-server.json | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Run the loopback fixture server and set up ADB reverse ports first' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp /data/local/tmp/vap-network-server.json files/network-server.json
}
if ($Suite -in @('whisper', 'sync', 'all')) {
    $fixtures = Join-Path $projectRoot 'app/build/device-test/whisper'
    foreach ($name in @('ggml-tiny-q5_1.bin', 'jfk.wav', 'jfk.mp4')) {
        if (-not (Test-Path -LiteralPath (Join-Path $fixtures $name))) { throw 'Run python tools/prepare-whisper-fixtures.py first' }
    }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/whisper-fixtures
    & $AdbPath -s $DeviceSerial push "$fixtures/." /data/local/tmp/vap-whisper-fixtures | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot push Whisper fixtures' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp -R /data/local/tmp/vap-whisper-fixtures/. files/whisper-fixtures/
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy Whisper fixtures' }
}
if ($Suite -in @('translation', 'all')) {
    $fixtures = Join-Path $projectRoot 'app/build/device-test/translation-models'
    foreach ($pair in @('en-ru', 'ru-en')) {
        if (-not (Test-Path -LiteralPath (Join-Path $fixtures "$pair/model.bin"))) { throw 'Run python tools/prepare-translation-fixtures.py first' }
    }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap mkdir -p files/translation-fixtures
    & $AdbPath -s $DeviceSerial push "$fixtures/." /data/local/tmp/vap-translation-fixtures | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot push translation fixtures' }
    & $AdbPath -s $DeviceSerial shell run-as com.phnem.vap cp -R /data/local/tmp/vap-translation-fixtures/. files/translation-fixtures/
    if ($LASTEXITCODE -ne 0) { throw 'Cannot copy translation fixtures' }
}
$testSuites = if ($Suite -eq 'all') { @('core', 'controls', 'ui', 'display', 'video', 'video-ui', 'audio', 'audio-ui', 'subtitles', 'resume', 'ass', 'online', 'network', 'servers', 'casting', 'whisper', 'sync', 'translation') } else { @($Suite) }
foreach ($testSuite in $testSuites) {
    # The connected test phone returns to its swipe lock after ten minutes.
    & $AdbPath -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP
    Start-Sleep -Milliseconds 700
    & $AdbPath -s $DeviceSerial shell wm dismiss-keyguard
    & $AdbPath -s $DeviceSerial shell input swipe 540 2100 540 500 500
    $instrumentArgs = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w')
    if ($testSuite -ne 'core') { $instrumentArgs += @('-e', 'suite', $testSuite) }
    if ($KeepFixture) { $instrumentArgs += @('-e', 'keepFixture', 'true') }
    $instrumentArgs += 'com.phnem.vap.test/com.phnem.vap.PlaybackDeviceTest'
    $result = & $AdbPath @instrumentArgs
    $result
    if (($result -join "`n") -notmatch '(?m)^PASS:') { throw "Playback device test failed: $testSuite" }
}
} finally {
    if ($guardTaken) { $suiteGuard.ReleaseMutex() }
    $suiteGuard.Dispose()
}

