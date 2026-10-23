param(
    [Parameter(Mandatory = $true)][string]$SamplePath,
    [string]$DeviceSerial = '3871a9d6',
    [switch]$KeepFixture,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot 'app\build\network-test-tools\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Create the ignored network-test-tools venv and install paramiko==4.0.0 pyftpdlib==2.1.0 first' }
$statePath = Join-Path $projectRoot 'app\build\device-test\network-server\server.json'
$ownedServer = $false
$ports = @(14421, 14422) + (14460..14469)
$mapped = @()
try {
    if (-not (Test-Path -LiteralPath $statePath) -or -not (Get-Process -Id ((Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).pid) -ErrorAction SilentlyContinue)) {
        $server = Join-Path $projectRoot 'tools\network-test-server.py'
        $fixtures = Join-Path $projectRoot 'app\build\device-test\network-fixtures'
        $stateDir = Split-Path -Parent $statePath
        Start-Process -FilePath $python -ArgumentList @('-u', "`"$server`"", '--directory', "`"$fixtures`"", '--state', "`"$stateDir`"") -WorkingDirectory $projectRoot -WindowStyle Hidden | Out-Null
        $ownedServer = $true
        Start-Sleep -Seconds 2
    }
    $existing = (& $AdbPath -s $DeviceSerial reverse --list) -join "`n"
    foreach ($port in $ports) {
        if ($existing -match "tcp:$port(?:\s|$)") { throw "ADB reverse port $port is already in use" }
        & $AdbPath -s $DeviceSerial reverse "tcp:$port" "tcp:$port"
        if ($LASTEXITCODE -ne 0) { throw "Cannot reverse port $port" }
        $mapped += $port
    }
    & (Join-Path $projectRoot 'tools\test-playback-device.ps1') -SamplePath $SamplePath -DeviceSerial $DeviceSerial -AdbPath $AdbPath -Suite protocols -KeepFixture:$KeepFixture
} finally {
    foreach ($port in $mapped) { & $AdbPath -s $DeviceSerial reverse --remove "tcp:$port" | Out-Null }
    if ($ownedServer -and (Test-Path -LiteralPath $statePath)) {
        $serverPid = (Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).pid
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid" -ErrorAction SilentlyContinue
        if ($processInfo.CommandLine -like '*tools/network-test-server.py*' -or $processInfo.CommandLine -like '*tools\network-test-server.py*') { Stop-Process -Id $serverPid -ErrorAction SilentlyContinue }
    }
}
