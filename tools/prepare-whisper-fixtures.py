"""Optional device-test assets. Downloads the 32 MB Tiny model, never packages it in an APK."""
import hashlib
from pathlib import Path
import urllib.request
import subprocess
import os

root = Path(__file__).resolve().parents[1] / 'app/build/device-test/whisper'
root.mkdir(parents=True, exist_ok=True)
assets = [
    ('ggml-tiny-q5_1.bin', 'https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin', '818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7'),
    ('jfk.wav', 'https://raw.githubusercontent.com/ggml-org/whisper.cpp/927cfce34f31707e17f2bff35c349632fb9e2c3a/samples/jfk.wav', '59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e'),
]
for name, url, digest in assets:
    target = root / name
    if not target.is_file():
        pending = target.with_suffix('.download')
        urllib.request.urlretrieve(url, pending)
        pending.replace(target)
    if digest:
        with target.open('rb') as source:
            if hashlib.file_digest(source, 'sha256').hexdigest() != digest:
                raise RuntimeError(name + ' SHA-256 mismatch')
    print(name, target.stat().st_size, 'bytes')

# Default audio is a tone. The test must explicitly select the second, speech track.
video = root / 'jfk.mp4'
if not video.is_file():
    subprocess.run([os.environ.get('VAP_FFMPEG', r'D:\ffmpeg\bin\ffmpeg.exe'), '-hide_banner', '-loglevel', 'error', '-y',
        '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=24', '-i', str(root / 'jfk.wav'),
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100', '-t', '11', '-map', '0:v', '-map', '2:a', '-map', '1:a',
        '-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-c:a', 'aac',
        '-metadata:s:a:0', 'language=zxx', '-metadata:s:a:1', 'language=eng',
        '-disposition:a:0', 'default', '-disposition:a:1', '0', '-movflags', '+faststart', str(video)], check=True)
print('jfk.mp4', video.stat().st_size, 'bytes')
