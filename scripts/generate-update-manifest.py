#!/usr/bin/env python3
"""Generate the GitHub updater manifest from a verified, already signed VPN APK."""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

CERTIFICATE = "e59de2486084c38f3c77e9df0eb5eff9a4559f3c68024f1208e0e9c04b0df665"
MAX_APK_BYTES = 128 * 1024 * 1024
ASSET_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,190}\.apk")


def package_identity(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != "com.foxhole.vpn":
        raise ValueError("APK must be the public com.foxhole.vpn package")
    code, name = int(package.group(2)), package.group(3)
    if code < 1 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?", name):
        raise ValueError("APK version identity is invalid")
    return code, name


def require_canonical_certificate(output, pin):
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", output, re.M)
    if len(signers) != 1 or signers[0].lower() != pin:
        raise ValueError("APK is not signed by the canonical release certificate")


def generate(apk, asset_name, expected_tag, apksigner, aapt2, pin):
    if not ASSET_NAME.fullmatch(asset_name) or not apk.is_file() or not 1 <= apk.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("APK asset name or size is invalid")
    verification = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", str(apk)],
                                  check=True, capture_output=True, text=True).stdout
    require_canonical_certificate(verification, pin)
    badging = subprocess.run([aapt2, "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    code, name = package_identity(badging)
    if expected_tag != f"v{name}":
        raise ValueError("release tag does not match APK versionName")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return {"versionCode": code, "versionName": name, "apkName": asset_name,
            "apkSha256": digest.hexdigest(), "notes": "See the release notes on GitHub."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--asset-name", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pin_file = Path(__file__).resolve().parents[1] / "config/release-cert-sha256.txt"
    pin = pin_file.read_text().strip().lower()
    if pin != CERTIFICATE:
        raise ValueError("release certificate configuration changed")
    if args.output.exists():
        raise ValueError("manifest output already exists; overwriting is forbidden")
    result = generate(args.apk, args.asset_name, args.tag, args.apksigner, args.aapt2, pin)
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
