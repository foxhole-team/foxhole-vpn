#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import sys

root = Path(__file__).resolve().parents[1]
native = Path(sys.argv[1])
supported_abis = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
requested = {abi for argument in sys.argv[2:] for abi in argument.replace(',', ' ').split()}
assert not sys.argv[2:] or requested, 'Requested ABI list is empty'
assert requested <= supported_abis, 'Unsupported requested ABI'
receipt = json.loads((native / 'foxcore-vpn-build.json').read_text())
assert receipt['core_revision'] == (root / 'config/foxcore-revision.txt').read_text().strip()
assert receipt['features'] == (root / 'config/foxcore-features.txt').read_text().strip()
assert not {'tor', 'i2p', 'onion-service', 'shipped'} & set(receipt['features'].split(','))
expected = {entry['path'] for entry in receipt['files']}
missing = {abi for abi in requested if f'{abi}/libfoxhole_native.so' not in expected}
assert not missing, 'Requested ABI lacks FoxCore: ' + ', '.join(sorted(missing))
actual = {str(path.relative_to(native)) for path in native.glob('*/*.so')}
assert len(expected) == len(receipt['files']), 'Duplicate artifact entries'
assert expected == actual and actual, 'Native payload differs from VPN producer receipt'
for entry in receipt['files']:
    relative = Path(entry['path'])
    assert not relative.is_absolute() and '..' not in relative.parts and len(relative.parts) == 2
    assert relative.parts[0] in supported_abis
    path = native / relative
    assert path.name == 'libfoxhole_native.so'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256'], 'Native artifact checksum mismatch'
print('Verified VPN-only producer identity and', len(actual), 'native ABI artifacts')
