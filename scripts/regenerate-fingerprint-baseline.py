#!/usr/bin/env python3
"""Regenerate compiled fingerprint digests from the actual pinned Core blobs."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
core = Path(sys.argv[1]).resolve(strict=True)
revision = (root / 'config/foxcore-revision.txt').read_text().strip()
assert subprocess.check_output(['git', '-C', str(core), 'rev-parse', 'HEAD'], text=True).strip() == revision
manifest = json.loads((root / 'config/foxcore-source-manifest.json').read_text())
assert manifest['commit'] == revision
files = [entry for entry in manifest['files'] if entry['path'].startswith('fingerprints/')]
assert len(files) == 9
digests = {}
for entry in files:
    blob = subprocess.check_output(['git', '-C', str(core), 'show', revision + ':' + entry['path']])
    assert blob == (core / entry['path']).read_bytes()
    assert hashlib.sha256(blob).hexdigest() == entry['sha256']
    profile = json.loads(blob)
    name, digest = profile['name'], profile['fingerprint_sha256']
    assert re.fullmatch('[a-z][a-z0-9_]{0,63}', name) and re.fullmatch('[a-f0-9]{64}', digest)
    assert name not in digests
    digests[name] = digest
lines = ['package com.foxhole.vpn.updates', '', 'internal object FingerprintBaseline {',
         f'    const val baselineSourceRevision = "{revision}"', '    val digests = mapOf(']
lines += [f'        "{name}" to "{digest}",' for name, digest in sorted(digests.items())]
lines += ['    )', '}', '']
(root / 'app/src/main/kotlin/com/foxhole/vpn/updates/FingerprintBaseline.kt').write_text('\n'.join(lines))
print('Regenerated nine fingerprint baselines from pinned Core', revision)
