#!/usr/bin/env python3
"""Preserve original VPN notices and add verified derived-native provenance."""
import argparse
import hashlib
import importlib.util
import re
import json
from pathlib import Path
import shutil
import subprocess


def validate_fork_material(repo, original, manifest, delivery_profile="legacy4", abis=None):
    source = json.loads((repo / "config/foxcore-source-manifest.json").read_text())
    targets = {
        "android4": ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"],
        "legacy4": ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"],
        "android64": ["aarch64-linux-android", "x86_64-linux-android"],
        "android-armv7": ["armv7-linux-androideabi"],
        "android3": ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"],
    }
    delivery_abis = {"android4": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], "legacy4": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
                     "android64": ["arm64-v8a", "x86_64"],
                     "android-armv7": ["armeabi-v7a"], "android3": ["arm64-v8a", "armeabi-v7a", "x86_64"]}
    if delivery_profile not in targets:
        raise ValueError("Unknown Android delivery profile")
    expected = {"androidDeliveryProfile": delivery_profile,
                "abis": delivery_abis[delivery_profile], "targets": targets[delivery_profile]}
    config_path = repo / "config/foxcore-android-delivery.json"
    if config_path.exists() or delivery_profile != "legacy4":
        if config_path.is_symlink() or not config_path.is_file():
            raise ValueError("Android delivery differs from the independent app profile config")
        config = json.loads(config_path.read_text())
        if not isinstance(config, dict) or type(config.get("schema")) is not int or config != {"schema": 1, **expected}:
            raise ValueError("Android delivery differs from the independent app profile config")
    for record in (source, manifest):
        if record.get("androidDeliveryProfile", "legacy4") != delivery_profile:
            raise ValueError("Core legal/source Android delivery profile differs")
        if delivery_profile != "legacy4" and any(record.get(key) != value for key, value in expected.items()):
            raise ValueError(f"{delivery_profile} legal/source requires its exact ABI/target authorities")
    if delivery_profile != "legacy4" and (not isinstance(abis, list) or
            len(abis) != len(delivery_abis[delivery_profile]) or set(abis) != set(delivery_abis[delivery_profile])):
        raise ValueError(f"Requested APK ABI set differs from {delivery_profile} legal delivery")
    rows = source.get("files")
    if not isinstance(rows, list):
        raise ValueError("Pinned Core source manifest is incomplete")
    pins = {row["path"]: row["sha256"] for row in rows}
    if len(pins) != len(rows):
        raise ValueError("Duplicate Core source input")
    fork_present = any(path.startswith("vendor/rustls/") for path in pins)
    prefix = "native/source-forks/rustls-0.23.46/"
    packaged_present = any(row["path"].startswith(prefix) for row in manifest["files"])
    origin_path = "config/source-forks/rustls-0.23.46.json"
    if fork_present or origin_path in pins or packaged_present:
        revision = (repo / "config/foxcore-revision.txt").read_text().strip()
        if re.fullmatch("[0-9a-f]{40}", revision) is None or source.get("commit") != revision or manifest.get("core_revision") != revision:
            raise ValueError("Modified Core legal material differs from the pinned source revision")
        if not fork_present or origin_path not in pins:
            raise ValueError("Packaged fork cannot be relabelled as stock source")
        spec = importlib.util.spec_from_file_location("rustls_fork_origin", repo / "scripts/rustls-fork-origin.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        def read_notice(relative):
            path = original / "native" / relative
            if path.is_symlink() or not path.is_file():
                raise ValueError("Pinned fork notice is missing")
            return path.read_bytes()
        origin = module.validate_packaged_origin(read_notice, pins)
        if origin.get("sourcePinsBound") is not True:
            raise ValueError("Fork legal namespace lacks pinned source binding")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abis', nargs='+', required=True)
    parser.add_argument('--android-delivery-profile', choices=['legacy4', 'android64', 'android-armv7', 'android3', 'android4'], default='legacy4')
    args = parser.parse_args()
    owned_output = args.repo.resolve() / 'app/build/generated/derivedLicenseAssets/licenses'
    if args.output.resolve() != owned_output or args.output.is_symlink():
        raise ValueError('Derived legal output must be the exact owned generated directory')
    original = args.repo / 'app/src/main/assets/licenses'
    manifest = json.loads((original / 'MANIFEST.json').read_text())
    expected = {item['path']: item for item in manifest['files']}
    actual = {p.relative_to(original).as_posix() for p in original.rglob('*') if p.is_file()}
    if actual != set(expected) | {'MANIFEST.json'}:
        raise ValueError('Original VPN legal namespace is stale or incomplete')
    for name, item in expected.items():
        path = original / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256'] or \
                path.stat().st_size != item['bytes']:
            raise ValueError('Original VPN notice bytes differ')
    validate_fork_material(args.repo, original, manifest, args.android_delivery_profile, args.abis)
    if args.output.exists():
        shutil.rmtree(args.output)
    shutil.copytree(original, args.output)
    (args.output / 'MANIFEST.json').unlink()
    subprocess.run(['python3', str(args.repo / 'scripts/copy-derived-native-licenses.py'),
                    '--repo', str(args.repo), '--native', str(args.native), '--output', str(args.output),
                    '--abis', *args.abis], check=True, timeout=60)
    manifest['files'] = [{'path': p.relative_to(args.output).as_posix(),
                          'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size}
                         for p in sorted(args.output.rglob('*')) if p.is_file()]
    manifest['nativeProvenance'] = 'Separately rebuilt graphics native; original Maven Java/AAR remains unchanged.'
    (args.output / 'MANIFEST.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
