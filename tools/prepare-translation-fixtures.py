"""Optional 165 MB EN/RU translation test models, verified against the shipped pinned catalog."""
import hashlib
import json
from pathlib import Path
import socket
import urllib.request

socket.setdefaulttimeout(45)
root = Path(__file__).resolve().parents[1]
models = json.loads((root / 'app/src/main/assets/models/translation-models.json').read_text())
for model in models:
    directory = root / 'app/build/device-test/translation-models' / model['id']
    directory.mkdir(parents=True, exist_ok=True)
    for spec in model['files']:
        target = directory / spec['name']
        if not target.exists():
            pending = target.with_suffix('.download')
            urllib.request.urlretrieve(spec['url'], pending)
            pending.replace(target)
        with target.open('rb') as source:
            if target.stat().st_size != spec['bytes'] or hashlib.file_digest(source, 'sha256').hexdigest() != spec['sha256']:
                raise RuntimeError('Test model checksum mismatch: ' + str(target))
    print(model['id'], sum(f['bytes'] for f in model['files']), 'verified bytes')
