param([string]$Ffmpeg = 'D:\ffmpeg\bin\ffmpeg.exe')
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot '..\app\build\device-test\audio-fixtures'
New-Item -ItemType Directory -Force -Path $output | Out-Null
# Two independently identifiable stereo tracks; the external MKA carries French audio.
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x180:rate=24' -f lavfi -i 'aevalsrc=0.1*sin(2*PI*440*t)|0.1*sin(2*PI*660*t):s=48000' -f lavfi -i 'aevalsrc=0.1*sin(2*PI*880*t)|0.1*sin(2*PI*1100*t):s=48000' -map 0:v -map 1:a -map 2:a -metadata:s:a:0 language=eng -metadata:s:a:0 title=English -metadata:s:a:1 language=rus -metadata:s:a:1 title=Russian -t 30 -c:v libx264 -preset ultrafast -c:a aac (Join-Path $output 'dual-audio.mkv')
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare dual-audio.mkv' }
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'aevalsrc=0.1*sin(2*PI*220*t)|0.1*sin(2*PI*330*t):s=48000' -metadata:s:a:0 language=fra -t 30 -c:a flac (Join-Path $output 'external.mka')
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare external.mka' }
Write-Output "Prepared audio fixtures in $output"
