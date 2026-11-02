#!/usr/bin/env python3
"""Regenerate compiled fingerprint digests from the actual pinned Core blobs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
core = Path(sys.argv[1]).resolve(strict=True)
revision = (root / 'config/foxcore-revision.txt').read_text().strip()
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('fingerprint_source_binding', root / 'scripts/core-source-binding.py')
binding_helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binding_helper)
binding = binding_helper.resolve(root, core, revision)
binding_helper.need(binding.get('legacy') is False, 'Verified Core source binding required')
source_revision = binding['sourceCommit']
manifest = json.loads((root / 'config/foxcore-source-manifest.json').read_text())
assert manifest['commit'] == revision
files = [entry for entry in manifest['files'] if entry['path'].startswith('fingerprints/')]
assert len(files) == 10
digests = {}
for entry in files:
    blob = subprocess.check_output(['git', '-C', str(core), 'show', source_revision + ':' + entry['path']])
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
print('Regenerated ten fingerprint baselines from bound Core source', source_revision, 'producer', revision)
