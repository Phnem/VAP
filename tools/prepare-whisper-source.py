"""Fetch a pinned MIT whisper.cpp source tree; no model weights in the APK."""
from pathlib import Path
import hashlib
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REV = '927cfce34f31707e17f2bff35c349632fb9e2c3a'
SHA = '41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde'
BASE = ROOT / 'app/build/native-src'
DEST = BASE / ('whisper.cpp-' + REV)
ARCHIVE = BASE / ('whisper-' + REV + '.tar.gz')

def prepare():
    if (DEST / '.vap-verified').is_file():
        return
    BASE.mkdir(parents=True, exist_ok=True)
    if not ARCHIVE.is_file():
        tmp = ARCHIVE.with_suffix('.download')
        urllib.request.urlretrieve('https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/' + REV, tmp)
        tmp.replace(ARCHIVE)
    with ARCHIVE.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != SHA:
            raise RuntimeError('whisper.cpp archive SHA-256 mismatch')
    with tarfile.open(ARCHIVE) as archive:
        # Reject links, devices and any path outside the exact pinned directory.
        for member in archive.getmembers():
            target = (BASE / member.name).resolve()
            if not target.is_relative_to(DEST.resolve()) or not (member.isfile() or member.isdir()):
                raise RuntimeError('Unsafe whisper.cpp archive member: ' + member.name)
        archive.extractall(BASE, filter='data')
    (DEST / '.vap-verified').write_text(SHA, encoding='ascii')
    print('Prepared pinned whisper.cpp ' + REV)

if __name__ == '__main__':
    prepare()
