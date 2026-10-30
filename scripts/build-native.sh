#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${FOXCORE_SOURCE_ROOT:?Set FOXCORE_SOURCE_ROOT to the verified Core source directory}"
OUTPUT="${FOXCORE_VPN_JNI_ROOT:-$ROOT/native-libs}"
FEATURES="$(tr -d '\n\r' < "$ROOT/config/foxcore-features.txt")"
python3 "$ROOT/scripts/verify-core-source.py" "$CORE" "$ROOT/config/foxcore-source-manifest.json"
export FOXCORE_ANDROID_FEATURES="$FEATURES"
export FOXCORE_JNI_OUTPUT="$OUTPUT"
bash "$CORE/scripts/android-build.sh"
python3 - "$ROOT" "$OUTPUT" <<'PY'
from pathlib import Path
import hashlib,json,sys
root,out=map(Path,sys.argv[1:])
files=[{'path':str(p.relative_to(out)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.glob('*/libfoxhole_native.so'))]
assert files, 'No VPN native artifacts produced'
receipt={'core_revision':(root/'config/foxcore-revision.txt').read_text().strip(),'features':(root/'config/foxcore-features.txt').read_text().strip(),'files':files}
(out/'foxcore-vpn-build.json').write_text(json.dumps(receipt,indent=2)+'\n')
PY
