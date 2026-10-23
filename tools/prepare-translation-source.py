"""Pinned CPU-only CTranslate2 + SentencePiece, with their required source dependencies."""
from pathlib import Path
import hashlib
import tarfile
import urllib.request

BASE = Path(__file__).resolve().parents[1] / 'app/build/native-src'
ROOT = BASE / 'translation'
SOURCES = [
    ('ctranslate2', 'OpenNMT/CTranslate2', '8d91aa08225004755360daa43deb62b35b1ed65d', '5a42a95b8f4bf5e328cb7d1a954ab641166bc293d0efb9a30f4938e08453b408'),
    ('ctranslate2/third_party/spdlog', 'gabime/spdlog', '76fb40d95455f249bd70824ecfcae7a8f0930fa3', '4530d53b86d45228a5b8982a8f4fe7d95d3ba8e1944858d3f663732c7e949cb7'),
    ('ctranslate2/third_party/cpu_features', 'google/cpu_features', '8a494eb1e158ec2050e5f699a504fbc9b896a43b', '9b2019dae7608630ab985129a5c3263fc788e4f82e26965a8d8c33b8c035802e'),
    ('ctranslate2/third_party/ruy', 'google/ruy', '363f252289fb7a1fba1703d99196524698cb884d', '3c2181c9134896784d5d0494e2638c43127de4da2ddf81b3a64c06311fb17432'),
    ('ctranslate2/third_party/ruy/third_party/cpuinfo', 'pytorch/cpuinfo', '082deffc80ce517f81dc2f3aebe6ba671fcd09c9', '4379348ec3127b37e854a0a66f85ea1d3c606e5f3a6dce235dc9c69ce663c026'),
    # 0.2.1 has bundled minimal absl/protobuf; 0.2.2 fetches another repo at configure time.
    ('sentencepiece', 'google/sentencepiece', '31646a467d2051eb904e0b45de3a73e91fe1c1e3', 'b4eb17e6bea5c9380ddd79d04d01d6aa5e280be8f3e3cef1f745a80e6d9451ce'),
]

def prepare():
    BASE.mkdir(parents=True, exist_ok=True)
    for relative, repo, rev, sha in SOURCES:
        dest = ROOT / relative
        marker = dest / '.vap-verified'
        if marker.is_file() and marker.read_text() == sha:
            continue
        archive = BASE / (relative.split('/')[-1] + '-' + rev + '.tar.gz')
        if not archive.exists():
            pending = archive.with_suffix('.download')
            urllib.request.urlretrieve('https://codeload.github.com/' + repo + '/tar.gz/' + rev, pending)
            pending.replace(archive)
        with archive.open('rb') as source:
            if hashlib.file_digest(source, 'sha256').hexdigest() != sha:
                raise RuntimeError('Source SHA-256 mismatch: ' + repo)
        with tarfile.open(archive) as package:
            for member in package.getmembers():
                if not (member.isdir() or member.isfile()):
                    raise RuntimeError('Source links/devices are not permitted')
                parts = Path(member.name).parts[1:]
                target = dest.joinpath(*parts).resolve()
                if not target.is_relative_to(dest.resolve()):
                    raise RuntimeError('Source archive path escaped destination')
                if member.isdir(): target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(member) as source, target.open('wb') as output:
                        while block := source.read(256 * 1024): output.write(block)
        marker.write_text(sha)
        print('Prepared', repo, rev)
    # Android bionic has no pthread_setaffinity_np. CPU affinity is not used by VAP.
    affinity = ROOT / 'ctranslate2/src/thread_pool.cc'
    code = affinity.read_text(encoding='utf-8')
    old = '#if !defined(__linux__) || defined(_OPENMP)'
    new = '#if !defined(__linux__) || defined(__ANDROID__) || defined(_OPENMP)'
    if old in code:
        affinity.write_text(code.replace(old, new), encoding='utf-8')
    elif new not in code:
        raise RuntimeError('Pinned CTranslate2 affinity patch no longer matches')

if __name__ == '__main__': prepare()
