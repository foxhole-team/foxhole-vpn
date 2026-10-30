# Third-party notices

FoxHole VPN reuses the reviewed source slices listed in `source-slices.json` from
FoxHole Guard and the pinned FoxCore JNI contract. The project retains the GPL
license in `LICENSE`. Source hashes identify every copied or extracted slice;
the Guard application, dependency graph and repository history are not included.

FoxCore is built separately from the commit in `config/foxcore-revision.txt`
with exactly `config/foxcore-features.txt`. Its selected native SBOMs and notices
are bundled under `app/src/main/assets/licenses/native/`. The full-feature Guard
native SBOM is not substituted.

## Visual assets

| Material | Source and license | Bundled notice |
| --- | --- | --- |
| Tiny5 font | Tiny5 authors, SIL Open Font License 1.1 | `licenses/Tiny5-OFL.txt` |
| JetBrains Mono font | JetBrains, SIL Open Font License 1.1 | `licenses/JetBrainsMono-OFL.txt` |
| Line icons | Tabler Icons, MIT | `licenses/Tabler-MIT.txt` |
| Country flags | flag-icons, MIT | `licenses/flag-icons-LICENSE.txt` |
| Decorative vector globe | Natural Earth 1:50m country geometry, public domain | `licenses/NaturalEarth-PUBLIC-DOMAIN.txt` |
| FoxHole logo and fox widget frames | FoxHole Guard assets, retained under the project GPL license | `licenses/FoxHole-VPN-GPL-3.0-or-later.txt` |

The paths above are relative to the application's bundled assets. Font and
copyright notices are retained without changing their upstream text. The globe
contains precomputed coastline paths; the original source GeoJSON and a map SDK
are not included.

## Dependency inventory

The VPN native build selects its own Cargo dependency graph for each Android
target. Its inventory includes resolved normal and build dependencies; it does
not claim that every listed package survives linking or that Cargo enumerates
Rust/NDK runtime material. Compiler/runtime notices are separate inputs.

The current `aarch64-linux-android` and `x86_64-linux-android` inventories each
cover 252 Cargo packages. Their 459 legal/attribution files are identical and are
stored once, alongside both SBOMs and their hash-bound notice manifests. Rust
standard-library and Android NDK notices come from the native producer's actual
installed toolchain and are preserved under `native/toolchain/`.

Four Cargo archives omit their workspace-root license files: `asn1-rs-impl`
0.2.0, `etherparse` 0.19.0, `jni-sys-macros` 0.4.1, and `trait-variant` 0.1.2.
Their MIT and Apache texts were retrieved at the exact VCS commits embedded in
the checksum-verified crates. `native/UPSTREAM-LICENSE-SUPPLEMENTS.json` records
the source URLs, crate checksums, Git blob identities and license-text hashes.
`native/foxcore/` also preserves Core's adaptation notices and the full uTLS BSD
notice for its fingerprint-data attribution. No uTLS library or Go runtime is
included in the VPN application.

The Android dependency inventory under `app/src/main/assets/licenses/android/`
comes from the resolved `releaseRuntimeClasspath`: 124 external Maven components,
89 resolved AAR/JAR archives and 126 captured POMs, including the
parent that declares ListenableFuture's license. Metadata-only nodes and BOMs
are identified separately. This is the graph before R8 shrinking, not a claim
that all listed code survives into the final APK.

All 49 license/attribution files present in those runtime archives are preserved.
The complete official Apache-2.0 text is mapped to matching verified POM declarations.
JourneyApps 4.3.0 also retains its publisher COPYING at the immutable release-source
commit. These supplements are not replacements for upstream NOTICE files.
The public manifests bind license files, archive hashes, SBOM and build-source
hashes. License declarations alone are not used as a substitute for these texts.

The full-feature Guard dependency inventory and its Tor, I2P, Sentinel and
database notice sets are not reused for this product.

## Separately rebuilt Android native dependencies

Graphics-path keeps its original Maven Java API `1.0.1`. Its native library is
rebuilt from AndroidX release commit `8a05a22af450d589ef911d772a001a49dcb05b71`
with NDK `29.0.14206865` and 16-KiB maximum/common linker page sizes.

Original Maven artifacts are verified independently. `licenses/derived-native/`
contains the derived native producer/source/toolchain records and original
notices. These records describe build and static checks; Android runtime and
release acceptance remain separate.
