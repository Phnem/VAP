param(
    [string]$Ffmpeg = 'D:\ffmpeg\bin\ffmpeg.exe',
    [string]$Output = (Join-Path $PSScriptRoot '..\app\build\device-test\video-fixtures')
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$Output = (Resolve-Path -LiteralPath $Output).Path
function Encode([string]$Name, [string[]]$Arguments) {
    & $Ffmpeg -hide_banner -loglevel error -y @Arguments (Join-Path $Output $Name)
    if ($LASTEXITCODE -ne 0) { throw "Fixture encoding failed: $Name" }
    Write-Output "Prepared $Name"
}
Encode 'h264-24.mp4' @('-f','lavfi','-i','testsrc2=size=320x180:rate=24000/1001','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','2','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-movflags','+faststart','-shortest')
$source = Join-Path $Output 'h264-24.mp4'
Encode 'h264.mkv' @('-i',$source,'-c','copy')
Encode 'mpeg4.avi' @('-i',$source,'-c:v','mpeg4','-q:v','5','-c:a','libmp3lame')
Encode 'vp9.webm' @('-i',$source,'-c:v','libvpx-vp9','-cpu-used','8','-crf','40','-b:v','0','-threads','2','-c:a','libopus')
Encode 'hevc-25.mp4' @('-i',$source,'-r','25','-c:v','libx265','-preset','ultrafast','-x265-params','log-level=error:pools=2:frame-threads=1','-crf','32','-c:a','copy')
Encode 'av1-30.mp4' @('-i',$source,'-r','30','-c:v','libaom-av1','-cpu-used','8','-crf','45','-b:v','0','-threads','2','-c:a','copy')
Encode 'h264-60.mp4' @('-i',$source,'-r','60','-c:v','libx264','-preset','ultrafast','-c:a','copy')
Encode 'hdr10.mp4' @('-i',$source,'-vf','format=yuv420p10le','-c:v','libx265','-preset','ultrafast','-x265-params','log-level=error:pools=2:frame-threads=1:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400','-color_primaries','bt2020','-color_trc','smpte2084','-colorspace','bt2020nc','-crf','25','-c:a','copy')
[System.IO.File]::WriteAllBytes((Join-Path $Output 'corrupt.mp4'), [System.Text.Encoding]::ASCII.GetBytes('VAP intentionally invalid media fixture'))
$bytes = [System.IO.File]::ReadAllBytes($source)
$partial = New-Object byte[] ([int]($bytes.Length / 2))
[Array]::Copy($bytes, $partial, $partial.Length)
[System.IO.File]::WriteAllBytes((Join-Path $Output 'partial.mp4'), $partial)
