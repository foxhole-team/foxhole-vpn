import copy
import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

BASE = load('vpn_android3_fixtures', HERE / 'test_armv7_profile.py')
SHAPE = {'androidDeliveryProfile': 'android3', 'abis': ['arm64-v8a', 'armeabi-v7a', 'x86_64'],
    'targets': ['aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android']}

class Android3ProfileTest(unittest.TestCase):
    def test_exact_three_abi_profile_and_notice_report(self):
        BASE.SBOM.validate_delivery_profile(SHAPE, 'android3')
        report = BASE.SBOM.notice_report(SimpleNamespace(files=[], uncovered=[], sbom_sha256='a' * 64, delivery_profile='android3'))
        for key, value in SHAPE.items():
            self.assertEqual(value, report[key])

    def test_all_three_cargo_targets_keep_protocol_only_and_observed_edges(self):
        for target in SHAPE['targets']:
            fixture = BASE.BASE.NativeSbomTest()
            fixture.setUp()
            tree, metadata = fixture.tree.encode(), json.dumps(fixture.metadata).encode()
            capture = copy.deepcopy(fixture.capture)
            capture.update(SHAPE, target=target)
            capture['tree']['sha256'] = BASE.SBOM.sha256(tree)
            capture['metadata']['sha256'] = BASE.SBOM.sha256(metadata)
            for key in ('tree', 'metadata'):
                capture[key]['argv'] = [value.replace('aarch64-linux-android', target) for value in capture[key]['argv']]
            inventory = BASE.SBOM.build_inventory(tree, metadata, capture, fixture.lock, ['vless'], fixture.revision, target, delivery_profile='android3')
            self.assertEqual('android3', inventory.delivery_profile)
            self.assertEqual({'foxcore-android', 'derive-tools', 'normal-crate', 'build-helper'}, {row['name'] for row in inventory.packages.values()})

    def test_missing_arm32_x86_and_relabelled_native_profiles_are_refused(self):
        for key, value in [('abis', ['arm64-v8a', 'x86_64']), ('abis', SHAPE['abis'] + ['x86']),
                ('abis', SHAPE['abis'] + ['armeabi-v7a']), ('targets', SHAPE['targets'] + ['i686-linux-android'])]:
            record = copy.deepcopy(SHAPE)
            record[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(BASE.SBOM.InputError):
                BASE.SBOM.validate_delivery_profile(record, 'android3')
        for profile in ('legacy4', 'android64', 'android-armv7'):
            with self.subTest(profile=profile), self.assertRaises(BASE.SBOM.InputError):
                BASE.SBOM.validate_delivery_profile(SHAPE, profile)

    def test_gradle_reserves_three_abi_profile_for_fdroid(self):
        text = (HERE.parents[1] / 'app/build.gradle.kts').read_text()
        self.assertIn('selectedAbis.toSet() != setOf("arm64-v8a", "armeabi-v7a", "x86_64") || appUpdateChannel == "fdroid"', text)

if __name__ == '__main__':
    unittest.main()
