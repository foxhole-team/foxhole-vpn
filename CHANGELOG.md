# Changelog

## 1.0.0 Stable (unreleased)

- Keep router-origin `appMetadata` in the app layer, separate from the connection URI and Core JSON. Only an authenticated export over the pinned paired-router channel can enroll the matching saved pairing, local profile ID and normalized configuration digest; imported/provider hints never enroll trust. JNI, Rust/native inputs and connection formats remain unchanged.
- Add a separate default-OFF per-pair Home VPN management opt-in with the existing public-IP confirmation and pinned TLS/paired authentication. Automatic recognition requires the selected trusted profile actually connected on its app-owned VPN Network: one bounded read-only state check per matching connection generation, without physical/default-network fallback, timers, automatic retry or provider polling. Manual foreground control remains explicit; retry preserves existing trust and automatic phone home switching/handoff remains disabled.

- Add third-party licenses to About.
- Mark OpenWrt management as a public beta in router sheets and keep setup and management aligned with Guard. Require explicit first-setup license consent; fresh phone setup offers daily automatic router updates enabled with an opt-out, while pairing preserves the existing policy.
- Import v2rayTun links and subscriptions alongside FoxHole Guard profiles.

- Add a router Setup/Pair wizard with local-network consent, SSH identity review, a transient root password, exact protocol choices and bounded Flash/RAM planning. Hysteria2 is the default.
- Open paired router controls with the saved encrypted key; synchronize the selected phone profile and retain its subscription source. Fresh setup creates a new PIN without resetting a configured router.

- Keep HY2 certificate fingerprints separate from public-key pins; share their standard hexadecimal `pinSHA256` parameter.

- Import compatible Clash/mihomo YAML nodes with bounded parsing and explicit rejection of unsupported settings; preserve Shadowsocks UDP and escaped credentials when sharing.

- Support Outline dynamic keys and standard JSON payloads; retain the original source for refresh/share and check public HTTPS endpoints. Prevent links inside JSON/YAML from becoming subscription sources.

- Directly pasted SIP008 JSON imports selectable servers; unsupported plugins, document fields and versions are refused.

- Simple VPN client for Android 8+ on ARM64, ARMv7, x86 and x86_64, with encrypted profiles and clipboard, subscription, file and camera QR import.
- Require matching Core and third-party license assets for all four ABIs; retain separate per-APK device review.
- A profile tap reveals protocol choices directly. Hold a profile for Copy, Refresh or confirmed Delete; Copy shares the whole profile or its original subscription URL.
- View/add profiles while connected. Active protocol changes ask before reconnection; Stop remains available while the app owns a tunnel.
- Night uses the monochrome Guard palette; the previous dark theme is named FoxHole. System remains the default. Theme-tinted globe land in FoxHole/System; blue home and green server/route in FoxHole, bright markers in Light, and neutral markers in Night/System. Landscape controls sit by the lower safe edge. Profile import actions use the settings-row style.
- A Start tapped during Stop is queued until native cleanup completes and can be cancelled. Connection waits for Android socket access to the owned VPN network; the HTTPS proof allows time for upstream protocol handshakes.
- Readable subscription protocol names without generated service identifiers; safe validation reason codes in release logs.
- Simple gray vector globe with soft continuous edge shading without translucent bands and a contrasting route. Close home/exit points remain visually distinct without changing location data. Home details hide during connection changes and reopen after disconnection. Server cards choose a side that keeps the home point and connection line visible. Direct rotation, bounded inertia and haptics remain.
- Separate network-core data and GitHub APK update cards. Both are checked every 24 hours; applying data and downloading an APK require a tap. APK hash, package, newer version, device compatibility and release signer are checked before Android installation. The F-Droid build leaves APK updates to F-Droid.
- Subscriptions refresh every 12 hours; foreground IP refreshes every 30 minutes and on connection/network events. Automatic subscription refresh defers active configuration changes.
- Status widgets show a phone, flag and city, with bounded home-location refresh while disconnected. “Determining…” appears while connecting or looking up home location. Start/Stop acts directly; only the logo opens the app. VPN consent remains explicit.
- Profile lists use a six-row viewport and visible overflow scrollbar; list scrolling cannot dismiss the profile picker, while the header retains downward dismissal. Sheets reopen collapsed, with spring-based expansion, height and page transitions. Shorter help, top notices and a letter-height status dot with a gentle foreground pulse capped at eight updates per second and respecting reduced motion.
- Uppercase action buttons, long-press profile help and a Pixel/Standard heading/button font choice in theme settings. Pixel remains the default; Standard uses JetBrains Mono. Connection widgets use the transparent monochrome fox logo. Brighter System home marker and a muted FoxHole home blue.
