#!/usr/bin/env python3
import argparse
from pathlib import Path
import importlib.util
import hashlib
import json
import sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description="Verify a retained normal role or explicitly selected F-Droid source provenance")
parser.add_argument("native", type=Path)
parser.add_argument("abis", nargs="*")
parser.add_argument("--source-native-manifest", type=Path)
parser.add_argument("--core-root", type=Path)
args = parser.parse_args()
if (args.source_native_manifest is None) != (args.core_root is None):
    parser.error("Explicit source mode requires both provenance and Core root")
native = args.native
supported_abis = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
requested = {abi for argument in args.abis for abi in argument.replace(',', ' ').split()}
assert not args.abis or requested, 'Requested ABI list is empty'
assert requested <= supported_abis, 'Unsupported requested ABI'
if args.source_native_manifest is not None:
    spec = importlib.util.spec_from_file_location('explicit_fdroid_native_source', root / 'scripts/fdroid-source-build.py')
    source = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(source)
    source.verify_source_mode(root, native, args.source_native_manifest, args.core_root)
    print('Verified explicit four-ABI F-Droid source provenance; reference hashes and reproducibility scope unchanged')
    sys.exit(0)
marker = root / 'config/foxcore-native-inputs.json'
if marker.exists() or marker.is_symlink():
    spec = importlib.util.spec_from_file_location('strict_normal_core_native', root / 'scripts/normal-core-native.py')
    normal = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(normal)
    config, _verified = normal.verify(root, native, 'vpn', sorted(requested) if requested else normal.ABIS)
    projection = normal.document(normal.read(native / 'foxcore-vpn-build.json'))
    expected = [{'path': abi + '/libfoxhole_native.so', 'sha256': config['libraries'][abi]['sha256']} for abi in normal.ABIS]
    if projection.get('core_revision') != config['producerCommit'] or projection.get('features') != config['features'] or projection.get('files') != expected:
        raise ValueError('Derived normal VPN consumer inventory differs')
    proof = projection.get('materialization')
    if proof != {'kind': 'retained-qualified-normal-vpn-role', 'normalRoleReceiptSHA256': config['normalRoleReceiptSHA256'], 'newNativeBuildExecuted': False, 'SDKGuardJNIUsed': False}:
        raise ValueError('Explicit retained VPN provenance required')
    print('Verified retained qualified VPN-only role and four native ABI artifacts; no new native build')
    sys.exit(0)

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
