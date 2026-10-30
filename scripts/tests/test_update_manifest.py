import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "generate-update-manifest.py"
spec = importlib.util.spec_from_file_location("update_manifest", SCRIPT)
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


class UpdateManifestTests(unittest.TestCase):
    def test_public_package_and_actual_apk_version_are_required(self):
        self.assertEqual((19, "0.1.2"), manifest.package_identity("package: name='com.foxhole.vpn' versionCode='19' versionName='0.1.2'"))
        for package in ("com.foxhole.guard", "com.foxhole.vpn.debug"):
            with self.assertRaises(ValueError):
                manifest.package_identity(f"package: name='{package}' versionCode='19' versionName='0.1.2'")

    def test_empty_multiple_and_foreign_signers_are_refused(self):
        valid = f"Signer #1 certificate SHA-256 digest: {manifest.CERTIFICATE}\n"
        manifest.require_canonical_certificate(valid, manifest.CERTIFICATE)
        for output in ("", valid + valid.replace("#1", "#2"), valid.replace(manifest.CERTIFICATE, "a" * 64)):
            with self.assertRaises(ValueError):
                manifest.require_canonical_certificate(output, manifest.CERTIFICATE)

    def test_generation_uses_verified_bytes_and_refuses_tag_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "owned.apk"
            apk.write_bytes(b"owned APK fixture")
            results = [subprocess.CompletedProcess([], 0, f"Signer #1 certificate SHA-256 digest: {manifest.CERTIFICATE}\n", ""),
                       subprocess.CompletedProcess([], 0, "package: name='com.foxhole.vpn' versionCode='19' versionName='0.1.2'\n", "")]
            with patch.object(manifest.subprocess, "run", side_effect=results) as run:
                update = manifest.generate(apk, "vpn.apk", "v0.1.2", "apksigner", "aapt2", manifest.CERTIFICATE)
                self.assertEqual(19, update["versionCode"])
                self.assertEqual("vpn.apk", update["apkName"])
                self.assertEqual(manifest.hashlib.sha256(apk.read_bytes()).hexdigest(), update["apkSha256"])
                self.assertEqual(["apksigner", "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", str(apk)], run.call_args_list[0].args[0])
            with patch.object(manifest.subprocess, "run", side_effect=results):
                with self.assertRaises(ValueError):
                    manifest.generate(apk, "vpn.apk", "v0.1.1", "apksigner", "aapt2", manifest.CERTIFICATE)

    def test_untrusted_asset_names_are_refused_before_any_tools_run(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "owned.apk"
            apk.write_bytes(b"fixture")
            with patch.object(manifest.subprocess, "run") as run:
                for name in ("../vpn.apk", "/vpn.apk", "x\\vpn.apk", "x\nvpn.apk"):
                    with self.assertRaises(ValueError):
                        manifest.generate(apk, name, "v0.1.2", "apksigner", "aapt2", manifest.CERTIFICATE)
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
