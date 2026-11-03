import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

ROOT=Path(__file__).resolve().parents[2]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/path)
    result=importlib.util.module_from_spec(spec);sys.modules[name]=result;spec.loader.exec_module(result);return result
SBOM=module('vpn_arm64_preview_sbom','native-sbom.py')
DERIVED=module('vpn_arm64_preview_derived','prepare-derived-native-license-assets.py')
PROFILE={'androidDeliveryProfile':'android-arm64','abis':['arm64-v8a'],'targets':['aarch64-linux-android']}

class Arm64PreviewProfileTest(unittest.TestCase):
    def test_exact_one_abi_profile_is_valid(self):
        SBOM.validate_delivery_profile(PROFILE,'android-arm64')

    def test_foreign_or_extra_abis_are_rejected(self):
        for abis,targets in [(['x86_64'],['x86_64-linux-android']),(['arm64-v8a','x86_64'],['aarch64-linux-android','x86_64-linux-android'])]:
            with self.assertRaises(SBOM.InputError):SBOM.validate_delivery_profile(dict(PROFILE,abis=abis,targets=targets),'android-arm64')

    def test_old_android4_receipt_cannot_be_relabelled(self):
        with self.assertRaises(SBOM.InputError):SBOM.validate_delivery_profile(
            {'androidDeliveryProfile':'android4','abis':SBOM.ANDROID4_ABIS,'targets':SBOM.ANDROID4_TARGETS},'android-arm64')

    def test_notice_report_declares_only_actual_arm64_scope(self):
        report=SBOM.notice_report(SimpleNamespace(sbom_sha256='0'*64,files=[],uncovered=[],delivery_profile='android-arm64'))
        self.assertEqual(PROFILE,{key:report[key]for key in PROFILE})

if __name__=='__main__':unittest.main()
