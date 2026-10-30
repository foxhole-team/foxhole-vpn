import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

SBOM = load('vpn_armv7_native_inventory', SCRIPTS / 'native-sbom.py')
LEGAL = load('vpn_armv7_derived_notices', SCRIPTS / 'prepare-derived-native-license-assets.py')
BASE = load('vpn_armv7_cargo_fixtures', SCRIPTS / 'tests/test_native_sbom.py')
ARM = {'androidDeliveryProfile': 'android-armv7', 'abis': ['armeabi-v7a'], 'targets': ['armv7-linux-androideabi']}

class Armv7ProfileTest(unittest.TestCase):
    def inventory(self, fixture):
        tree = fixture.tree.encode()
        metadata = json.dumps(fixture.metadata).encode()
        capture = copy.deepcopy(fixture.capture)
        capture.update(ARM, target='armv7-linux-androideabi')
        capture['tree']['sha256'] = SBOM.sha256(tree)
        capture['metadata']['sha256'] = SBOM.sha256(metadata)
        for key in ('tree', 'metadata'):
            capture[key]['argv'] = [value.replace('aarch64-linux-android', 'armv7-linux-androideabi') for value in capture[key]['argv']]
        return SBOM.build_inventory(tree, metadata, capture, fixture.lock, ['vless'], fixture.revision,
                                    'armv7-linux-androideabi', delivery_profile='android-armv7')

    def test_armv7_selected_graph_retains_edges_and_protocol_only_scope(self):
        fixture = BASE.NativeSbomTest()
        fixture.setUp()
        inventory = self.inventory(fixture)
        self.assertEqual('android-armv7', inventory.delivery_profile)
        self.assertEqual({'foxcore-android', 'derive-tools', 'normal-crate', 'build-helper'},
                         {package['name'] for package in inventory.packages.values()})
        fixture.add_registry('arti-client')
        with self.assertRaises(SBOM.InputError):
            self.inventory(fixture)

    def test_exact_profile_is_distinct_from_existing_delivery_profiles(self):
        SBOM.validate_delivery_profile(ARM, 'android-armv7')
        for profile in ('android64', 'legacy4'):
            with self.subTest(profile=profile), self.assertRaises(SBOM.InputError):
                SBOM.validate_delivery_profile(ARM, profile)

    def test_mixed_missing_and_duplicate_armv7_authorities_are_refused(self):
        for key, value in [('abis', ['arm64-v8a']), ('abis', ['armeabi-v7a', 'x86']),
                           ('abis', ['armeabi-v7a', 'armeabi-v7a']), ('targets', ['aarch64-linux-android']),
                           ('targets', ['armv7-linux-androideabi', 'i686-linux-android']), ('androidDeliveryProfile', 'legacy4')]:
            record = copy.deepcopy(ARM)
            record[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(SBOM.InputError):
                SBOM.validate_delivery_profile(record, 'android-armv7')

    def test_armv7_notice_report_preserves_exact_delivery_selection(self):
        selection = SimpleNamespace(files=[], uncovered=[], sbom_sha256='a' * 64, delivery_profile='android-armv7')
        report = SBOM.notice_report(selection)
        for key, value in ARM.items():
            self.assertEqual(value, report[key])

    def test_legal_preparation_requires_independent_matching_config_and_exact_requested_abi(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'config').mkdir()
            source = {**ARM, 'files': []}
            manifest = {**ARM, 'files': []}
            source_path = root / 'config/foxcore-source-manifest.json'
            source_path.write_text(json.dumps(source))
            config = root / 'config/foxcore-android-delivery.json'
            config.write_text(json.dumps({'schema': 1, **ARM}))
            LEGAL.validate_fork_material(root, root, manifest, 'android-armv7', ['armeabi-v7a'])
            for abis in (None, [], ['arm64-v8a'], ['armeabi-v7a', 'x86'], ['armeabi-v7a', 'armeabi-v7a']):
                with self.subTest(abis=abis), self.assertRaises(ValueError):
                    LEGAL.validate_fork_material(root, root, manifest, 'android-armv7', abis)
            config.unlink()
            with self.assertRaises(ValueError):
                LEGAL.validate_fork_material(root, root, manifest, 'android-armv7', ['armeabi-v7a'])

    def test_existing_android64_profile_keeps_its_exact_two_targets(self):
        record = {'androidDeliveryProfile': 'android64', 'abis': ['arm64-v8a', 'x86_64'],
                  'targets': ['aarch64-linux-android', 'x86_64-linux-android']}
        SBOM.validate_delivery_profile(record, 'android64')
        with self.assertRaises(SBOM.InputError):
            SBOM.validate_delivery_profile(record, 'android-armv7')

if __name__ == '__main__':
    unittest.main()
