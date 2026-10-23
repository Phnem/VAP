param(
    [Parameter(Mandatory = $true)][string]$SamplePath,
    [string]$FfmpegPath = 'D:\ffmpeg\bin\ffmpeg.exe',
    [string]$DeviceSerial = '3871a9d6',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $projectRoot 'app\build\device-test\subtitle-fixtures'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$sample = (Resolve-Path -LiteralPath $SamplePath).Path
$english = Join-Path $directory 'english.srt'
$russian = Join-Path $directory 'russian.srt'
$englishText = @'
1
00:00:01,000 --> 00:00:04,000
VAP subtitle EN

2
00:00:05,000 --> 00:00:09,000
Second English cue

'@
$russianText = @'
1
00:00:01,000 --> 00:00:04,000
Субтитры РУ

2
00:00:05,000 --> 00:00:09,000
Вторая реплика

'@
[System.IO.File]::WriteAllText($english, $englishText, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($russian, $russianText, [System.Text.UTF8Encoding]::new($false))
& $FfmpegPath -y -v error -i $sample -i $english -i $russian -map 0:v -map 0:a -map 1 -map 2 -c copy -c:s srt -metadata:s:s:0 language=eng -metadata:s:s:1 language=rus -t 30 (Join-Path $directory 'embedded.mkv')
if ($LASTEXITCODE -ne 0) { throw 'Cannot create embedded subtitle fixture' }
& $FfmpegPath -y -v error -f lavfi -i 'color=c=black:s=640x360:r=24:d=12' -c:v libx264 -pix_fmt yuv420p (Join-Path $directory 'black.mp4')
if ($LASTEXITCODE -ne 0) { throw 'Cannot create ASS UI background fixture' }
$testSource = [System.IO.File]::ReadAllText((Join-Path $projectRoot 'app\src\androidTest\java\com\phnem\vap\AssDeviceTest.kt'))
$styled = [regex]::Match($testSource, '(?s)private val styledAssFixture = """(.*?)"""\.trimIndent\(\)').Groups[1].Value
if (-not $styled) { throw 'Cannot find styled ASS test fixture' }
$styledPath = Join-Path $directory 'styled.ass'
[System.IO.File]::WriteAllText($styledPath, $styled, [System.Text.UTF8Encoding]::new($false))
$nativePython = Join-Path $projectRoot 'app\build\native-tools\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $nativePython)) { throw 'Build the app once to prepare native tools' }
& $nativePython -c 'import fontTools' 2>$null
if ($LASTEXITCODE -ne 0) { & $nativePython -m pip install fonttools==4.55.3 }
$baseFont = Join-Path $directory 'base-font.ttf'
& $AdbPath -s $DeviceSerial pull /system/fonts/NotoSerif-Regular.ttf $baseFont
if ($LASTEXITCODE -ne 0) { throw 'The font fixture needs the connected test phone NotoSerif font' }
$testFont = Join-Path $directory 'VAPFixture.ttf'
& $nativePython (Join-Path $PSScriptRoot 'native\prepare-font-fixture.py') $baseFont $testFont
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare uniquely named font fixture' }
[System.IO.File]::WriteAllText($styledPath, $styled.Replace('Style: Default,Roboto,', 'Style: Default,VAPFixture,'), [System.Text.UTF8Encoding]::new($false))
& $FfmpegPath -y -v error -i (Join-Path $directory 'black.mp4') -i $styledPath -map 0:v -map 1 -c copy -metadata:s:s:0 language=eng -attach $testFont -metadata:s:t:0 mimetype=application/x-truetype-font -metadata:s:t:0 filename=VAPFixture.ttf (Join-Path $directory 'styled.mkv')
if ($LASTEXITCODE -ne 0) { throw 'Cannot create embedded ASS fixture' }
