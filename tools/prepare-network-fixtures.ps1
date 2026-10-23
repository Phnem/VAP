param(
    [Parameter(Mandatory = $true)][string]$SamplePath,
    [string]$FfmpegPath = 'D:\ffmpeg\bin\ffmpeg.exe'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $projectRoot 'app\build\device-test\network-fixtures'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$sample = (Resolve-Path -LiteralPath $SamplePath).Path
Copy-Item -LiteralPath $sample -Destination (Join-Path $directory 'video.mp4') -Force
$hls = Join-Path $directory 'hls'
$dash = Join-Path $directory 'dash'
New-Item -ItemType Directory -Force -Path $hls, $dash | Out-Null
& $FfmpegPath -y -v error -i $sample -t 24 -map 0:v:0 -map 0:a:0 -c:v libx264 -preset fast -crf 30 -vf 'scale=640:-2' -g 48 -keyint_min 48 -sc_threshold 0 -c:a aac -b:a 64k -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename (Join-Path $hls 'segment-%03d.ts') (Join-Path $hls 'stream.m3u8')
if ($LASTEXITCODE -ne 0) { throw 'Cannot create HLS fixture' }
Push-Location -LiteralPath $dash
try {
    & $FfmpegPath -y -v error -i $sample -t 24 -map 0:v:0 -map 0:a:0 -c:v libx264 -preset fast -crf 30 -vf 'scale=640:-2' -g 48 -keyint_min 48 -sc_threshold 0 -c:a aac -b:a 64k -f dash -seg_duration 2 -use_template 1 -use_timeline 1 'manifest.mpd'
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Cannot create DASH fixture' }
Write-Output "Network fixtures ready: $directory"
