import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "verify-native-payload.py"
ROOT = SCRIPT.parent.parent


class NativePayloadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="foxhole-native-validator-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.native = Path(self.temporary.name)
        self.receipt = {
            "core_revision": (ROOT / "config/foxcore-revision.txt").read_text().strip(),
            "features": (ROOT / "config/foxcore-features.txt").read_text().strip(),
            "files": [],
        }
        self.add_core("arm64-v8a")
        self.add_core("x86_64")

    def add_core(self, abi):
        # Synthetic validator fixtures are never producer/native-build evidence.
        data = ("owned-validator-fixture-" + abi).encode()
        relative = abi + "/libfoxhole_native.so"
        path = self.native / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        self.receipt["files"].append({"path": relative, "sha256": hashlib.sha256(data).hexdigest()})
        self.write_receipt()

    def write_receipt(self):
        (self.native / "foxcore-vpn-build.json").write_text(json.dumps(self.receipt))

    def verify(self, *abis):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.native), *abis],
                              cwd=ROOT, capture_output=True, text=True)

    def test_missing_requested_core_is_rejected(self):
        result = self.verify("armeabi-v7a")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Requested ABI lacks FoxCore", result.stderr)

    def test_collateral_jni_cannot_satisfy_a_requested_core_abi(self):
        path = self.native / "armeabi-v7a/libandroidx.graphics.path.so"
        path.parent.mkdir()
        path.write_bytes(b"owned-collateral-jni-fixture")
        result = self.verify("armeabi-v7a")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Requested ABI lacks FoxCore", result.stderr)

    def test_valid_selected_subset_retains_complete_receipt_validation(self):
        result = self.verify("arm64-v8a")
        self.assertEqual(0, result.returncode, result.stderr)
        result = self.verify("arm64-v8a", "x86_64")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_other_abis_are_valid_when_the_producer_has_their_core_files(self):
        self.add_core("armeabi-v7a")
        self.add_core("x86")
        result = self.verify("armeabi-v7a", "x86")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_old_single_root_cli_still_works_and_detects_payload_tampering(self):
        result = self.verify()
        self.assertEqual(0, result.returncode, result.stderr)
        (self.native / "arm64-v8a/libfoxhole_native.so").write_bytes(b"owned-tampered-fixture")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)

    def test_an_unselected_core_artifact_cannot_be_tampered(self):
        (self.native / "x86_64/libfoxhole_native.so").write_bytes(b"owned-tampered-fixture")
        result = self.verify("arm64-v8a")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)

    def test_pin_features_and_abi_names_remain_strict(self):
        baseline = copy.deepcopy(self.receipt)
        for key, value in (("core_revision", "0" * 40), ("features", self.receipt["features"] + ",tor")):
            with self.subTest(key=key):
                self.receipt = copy.deepcopy(baseline)
                self.receipt[key] = value
                self.write_receipt()
                self.assertNotEqual(0, self.verify("arm64-v8a").returncode)
        self.receipt = baseline
        self.write_receipt()
        self.assertNotEqual(0, self.verify("unknown-abi").returncode)


if __name__ == "__main__":
    unittest.main()
