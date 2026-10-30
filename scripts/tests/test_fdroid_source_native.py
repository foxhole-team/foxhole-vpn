import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'fdroid-source-build.py'
spec = importlib.util.spec_from_file_location('fdroid_source_native', SCRIPT)
SOURCE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(SOURCE)


class SourceNativeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.root.chmod(0o700)
        (self.root / 'config').mkdir(mode=0o700)
        self.producer = '1' * 40
        self.source = '2' * 40
        self.features = 'vless,vmess,hysteria2,tuic,trojan,shadowsocks,socks,http,naive,anytls,shadowtls,wireguard'
        (self.root / 'config/foxcore-features.txt').write_text(self.features + '\n')
        self.native = self.root / 'native-libs'
        self.native.mkdir(mode=0o700)
        self.records = []
        baseline = {}
        for target, abi in zip(SOURCE.TARGETS, SOURCE.ABIS):
            elf_class, machine = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}[abi]
            data = bytearray(96)
            data[:6] = b'\x7fELF' + bytes([elf_class, 1])
            struct.pack_into('<H', data, 18, machine)
            folder = self.native / abi
            folder.mkdir(mode=0o700)
            path = folder / 'libfoxhole_native.so'
            path.write_bytes(data)
            self.records.append({'path': abi + '/libfoxhole_native.so', 'sha256': hashlib.sha256(data).hexdigest()})
            baseline[target] = {'abi': abi, 'bytes': 64, 'sha256': '0' * 64}
        self.context = {'producer': self.producer, 'binding': {'sourceCommit': self.source, 'sourceTree': '3' * 40},
                        'snapshot': {'nativeLibraries': baseline}}
        self.receipt = {'core_revision': self.producer, 'features': self.features, 'files': self.records}
        self.save()

    def save(self):
        (self.native / 'foxcore-vpn-build.json').write_text(json.dumps(self.receipt))

    def test_source_rebuild_has_own_hashes_and_keeps_reference_separate(self):
        original = copy.deepcopy(self.context)
        result = SOURCE.verify_vpn_native(self.root, self.context)
        self.assertEqual(result['sourceCommit'], self.source)
        self.assertEqual(result['referenceProducerCommit'], self.producer)
        self.assertEqual(len(result['files']), 4)
        self.assertTrue(all(not row['matchesReferenceNative'] for row in result['files']))
        self.assertEqual(self.context, original)

    def test_changed_native_bytes_fail_the_normal_producer_hash(self):
        path = self.native / self.records[0]['path']
        path.write_bytes(path.read_bytes() + b'x')
        with self.assertRaisesRegex(ValueError, 'digest'):
            SOURCE.verify_vpn_native(self.root, self.context)

    def test_missing_32bit_artifact_is_rejected(self):
        (self.native / self.records[1]['path']).unlink()
        with self.assertRaises(ValueError):
            SOURCE.verify_vpn_native(self.root, self.context)

    def test_wrong_feature_set_is_rejected(self):
        self.receipt['features'] += ',tor'
        self.save()
        with self.assertRaisesRegex(ValueError, 'features'):
            SOURCE.verify_vpn_native(self.root, self.context)

    def test_wrong_compiled_reference_revision_is_rejected(self):
        self.receipt['core_revision'] = '4' * 40
        self.save()
        with self.assertRaisesRegex(ValueError, 'reference'):
            SOURCE.verify_vpn_native(self.root, self.context)

    def test_duplicate_artifact_is_rejected(self):
        self.receipt['files'].append(dict(self.records[0]))
        self.save()
        with self.assertRaises(ValueError):
            SOURCE.verify_vpn_native(self.root, self.context)

    def test_wrong_elf_machine_is_rejected_even_with_matching_manifest_hash(self):
        path = self.native / self.records[0]['path']
        data = bytearray(path.read_bytes())
        struct.pack_into('<H', data, 18, 3)
        path.write_bytes(data)
        self.records[0]['sha256'] = hashlib.sha256(data).hexdigest()
        self.save()
        with self.assertRaisesRegex(ValueError, 'ELF'):
            SOURCE.verify_vpn_native(self.root, self.context)

    def test_linked_native_input_is_rejected(self):
        path = self.native / self.records[0]['path']
        data = path.read_bytes()
        path.unlink()
        other = self.root / 'other.so'
        other.write_bytes(data)
        path.symlink_to(other)
        with self.assertRaisesRegex(ValueError, 'Linked'):
            SOURCE.verify_vpn_native(self.root, self.context)


if __name__ == '__main__':
    unittest.main()
