# WireGuard domain routing and managed packet DNS

For a WireGuard profile with an actual packet DNS resolver, VPN retains the
profile's managed `packet_resolvers` from the first native session, including
sessions without website rules. The resolver address, advertised Android DNS
server, `real_ip` mode and WireGuard egress remain the profile's own values.
No public resolver or Direct fallback is introduced.

The native no-domain policy with no packet resolver remains unmanaged and
unchanged. Domain masks require an actual profile packet resolver and fail
without one. Changing website rules must not hot-enable managed packet DNS:
the Core start-time opt-in guard rejects an empty-to-nonempty reload. Adding
a previously absent profile DNS resolver therefore requires a native restart.

The profile parser accepts optional WireGuard DNS, while the current engine
translator requires a `dns-wireguard` server. This policy-only change does not
alter that pre-existing preparation restriction or add a resolver fallback.

Core authenticates the packet DNS reply and sends its managed downstream
answer with TTL 0. This prevents newly managed replies from creating a shared
system-resolver cache entry that outlives a later policy change. The internal
authenticated binding retains the original TTL and is separate from ordinary
reverse DNS hints. It is scoped by an opaque hash of the exact immutable
WireGuard peer/client configuration and DNS authority, then by UID, packages
and IP. Current application, numeric, domain and kill-switch policy is checked
for every new flow. A changed peer or DNS authority cannot read an old
binding. Generic/global hints and source-context retagging cannot authorize
Direct routing.

Within the same authority, Core can retain authenticated packet bindings
through policy forks, physical-network roam and explicit DNS continuation.
The Android routing handover captures one lease from the exact previous native
owner after the full acknowledged barrier. It imports only after a clean joined
stop and uses the existing JNI continuation start. Stop, supersession, failure
and late callbacks close the lease through the existing native executor; an
unsettled capture/start/release prevents target unblock and CONNECTED. Actual
JNI/device continuation qualification remains required. JVM lifecycle tests do
not prove that a device retained the map or routed authenticated traffic.

WireGuard domain masks are rejected below Android 11/API 30 at native-frame
preparation and before application routing changes are stored/applied. Numeric
CIDR/range and no-domain WG policies, and proxy fake-IP domain policies, keep
their existing platform behavior. Android supplies the SDK capability; the
validator uses the effective translated protocol set and typed website rules.

This behavior starts with the first managed session on the matching Core
build. Replacing the APK stops the old process and its descriptors, but that
is not proof of a new Android netId or of an emptied system/app resolver
cache. Answers cached before migration, application-private caches that
ignore TTL, hardcoded addresses and application-owned encrypted DNS cannot be
converted into authenticated bindings by this change. Missing, expired,
wrong-UID/package or wrong-authority evidence remains fail-closed. The app
never clears another app's cache, revokes VPN permission or clears profiles.

The Android connection-owner API is available from API 29, but its presence
does not prove the original application UID of a system DNS request. Android
Q/API 29 uses the resolver DNS identity; API 30+ is the supported baseline for
attributable per-app plaintext DNS, and OEM `enforceDnsUid` or DoT can still
use the system DNS UID. A system DNS UID is not evidence authorizing every
app. Data-flow UID/package checks and current policy remain mandatory.

AOSP describes [application versus system DNS socket ownership](https://android.googlesource.com/platform/packages/modules/DnsResolver/+/refs/heads/aml_cbr_341810000/binder/android/net/ResolverOptionsParcel.aidl)
and its [resolver UID enforcement behavior](https://android.googlesource.com/platform/packages/modules/DnsResolver/+/refs/tags/android-16.0.0_r1/DnsProxyListener.cpp).
Android VPN establishment can reuse a NetworkAgent, as shown in
[AOSP VPN handover](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/connectivity/Vpn.java).
This design preserves the existing full-capture barrier and does not claim a
fresh netId on establish or on every website-mask change.

## Profile reachability proof

WireGuard cannot be used as the Core loopback HTTP proxy outbound. For a WG
session with website rules or blocked applications, Android instead validates
one exact managed profile DNS resolver through the owned VPN network. Core's
packet-resolver path forces this traffic inside WireGuard even for a Direct
application and accepts its TUN reply only after peer authentication. Existing
kill-switch, attribution, numeric/app Block and current DNS-authority gates
still apply. No LAN ban, policy or Core/native source is changed.

The frozen plan requires raw real-IP managed DNS, one actual resolver matching
the advertised profile DNS, a covering planned TUN route, and a captured owner
package. Loopback, link-local, multicast, unspecified, broadcast and any current
self-interface address are refused. At bind and before/after validation the
owned VPN interface/routes and local interface snapshot are rechecked. A missing
or ambiguous capture plan fails; it never selects another/public resolver.

Each attempt uses a CSPRNG transaction ID and128-bit nonce in a fresh IN A
question under .invalid, bypassing the system DNS cache. A connected UDP socket
on the exact owned VPN network restricts the peer to that profile resolver:53.
Header/question/ID/nonce/RCODE/section bounds are checked. NOERROR or NXDOMAIN is
an authenticated reachability response; other errors are not success. Valid UDP
TC triggers TCP-DNS on the SAME resolver:53 and the SAME query, with one shared
six-second deadline. TCP length framing is bounded to4096bytes; TCP TC,
malformation, truncation, oversize or errors fail. No EDNS/DO or different/public fallback resolver is used. A public address is
used only if it is the exact declared profile resolver.

The I/O coroutine is a child of the caller on the existing Dispatchers.IO.
UDP closes before TCP is created. Timeout, cancellation and frame Stop close
the owned socket and join that child, including cancellation before dispatch
and a socket created after revocation. No new runtime owner or executor exists.
The full barrier, clean-stop continuation lease and current policy remain.

This proof measures authenticated profile-DNS reachability, not an exit-IP
service or every application's Internet flow. WG rule-mode Geo is therefore
unavailable (null location and explicit refresh failure); a user-Direct-routed
public IP is never labelled VPN. Proxy primary HTTP proof and no-rule WG HTTP
proof/Geo retain their existing behavior. CONNECTED requires successful DNS
proof and stable owner/generation/context; native startup or a local socket
alone cannot produce success.

Codec/plan/transport lifecycle tests are source-only until Root admits a build.
No new Android/JNI/device/tunnel evidence is claimed. Existing Rust WG lab7
coverage was IPv4 UDP only; TCP-DNS fallback unit fixtures do not qualify actual
TCP-over-WG runtime. Exact prior21/b18/b19 evidence retains its old byte scope.
