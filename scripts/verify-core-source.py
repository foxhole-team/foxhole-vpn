#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import sys

app_root = Path(sys.argv[2]).resolve().parents[1]
if (app_root / 'config/foxcore-source-checkout.json').exists():
    import importlib.util
    helper = Path(__file__).with_name('core-source-binding.py')
    spec = importlib.util.spec_from_file_location('exact_Core_source_checkout_binding', helper)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    producer = (app_root / 'config/foxcore-revision.txt').read_text().strip()
    binding = module.resolve(app_root, Path(sys.argv[1]).resolve(), producer)
    print('Verified Core checkout and compiled producer:', binding['sourceCommit'], producer)
    raise SystemExit(0)

root = Path(sys.argv[1]).resolve()
manifest = json.loads(Path(sys.argv[2]).read_text())
for entry in manifest['files']:
    relative = Path(entry['path'])
    assert not relative.is_absolute() and '..' not in relative.parts
    path = root / relative
    assert path.is_file(), 'Pinned native input missing: ' + str(relative)
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256'], 'Pinned native input changed: ' + str(relative)
print('Verified pinned Core inputs:', manifest['commit'], len(manifest['files']))
