# F-Droid source builds

`scripts/fdroid-source-build.py` separates network preparation from the offline
source build and requires the pinned Core checkout, Rust, NDK and all four Android
ABIs. VPN builds only its declared protocol feature set.

The compiled-source revision and canonical legal snapshot identify the original
reference material. Their native checksums describe those reference binaries.
Host-specific linker output can differ between Darwin and Linux. A source build
records its own checked output hashes and verified Core source commit; it does
not relabel that output as the reference producer.

The source helper checks the normal producer manifest, exact feature set, complete
four-ABI inventory, artifact hashes and ELF identities. The normal Core build
also runs its page, JNI export and host-path gates. Reference checksum differences
remain visible in `app/build/reports/fdroid/native-source-build.json`.

Dependency-prefetch tasks resolve each project's configurations inside that
project's own task so parallel Gradle execution holds the correct project locks.
Source/APK scanning remains enabled, and the normal source tarball is retained.

Reproducibility requires an independent source-built reference from the same
Linux toolchain. Sign that reference with the canonical release certificate,
then run an ordinary F-Droid source build and the official
`fdroidserver.common.verify_apks` comparison. Successful source compilation alone
does not establish APK reproducibility, runtime qualification or publication.

Caller-supplied `CARGO_HOME` and `CARGO_TARGET_DIR` are retained. Otherwise the
helper uses the user's Cargo home and a sibling dependency-cache target folder;
it does not require writable `/out` or `/target` directories on a build server.
