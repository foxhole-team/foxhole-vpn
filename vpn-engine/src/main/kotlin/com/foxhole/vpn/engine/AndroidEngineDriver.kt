package com.foxhole.vpn.engine

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.InetAddresses
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import com.foxhole.core.runtime.FoxholeNativeEngine
import com.foxhole.core.runtime.VpnEngineConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.vpn.profile.VpnProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

class AndroidEngineDriver(private val service: VpnService,
    private val applicationScope: () -> VpnApplicationScope = { VpnApplicationScope.WholeDevice },
    private val routingPolicy: () -> VpnRoutingPolicy = { VpnRoutingPolicy(applicationScope()) },
    private val configurationForProfile: (VpnProfile) -> com.foxhole.core.runtime.PreparedVpnConfig = VpnEngineConfig::prepare) : EngineDriver {
    private val connectivity = requireNotNull(service.getSystemService(ConnectivityManager::class.java))
    private val pendingTun = AtomicReference<ParcelFileDescriptor?>(null)

    override suspend fun open(profile: VpnProfile): EngineSession = withContext(Dispatchers.IO) {
        ManagedSession(profile, openFrame(profile, routingPolicy(), false, null))
    }

    private suspend fun openFrame(profile: VpnProfile, routing: VpnRoutingPolicy, blocked: Boolean,
        hooks: HandoverHooks?, prepared: FramePreparation? = null): NativeFrame = withContext(Dispatchers.IO) {
        fun current() { check(hooks?.isCurrent?.invoke() != false && VpnService.prepare(service) == null) { "Routing frame owner superseded" } }
        current()
        if (VpnService.prepare(service) != null) throw EngineException(EngineFailure.START)
        val preparation = prepared ?: prepareFrame(profile, routing, blocked)
        val config = preparation.config
        require(Build.VERSION.SDK_INT >= 29 || routing.blockedApplications.isEmpty()) { "Application blocking requires Android 10 flow attribution" }
        val policy = preparation.policy
        val engineJson = preparation.engineJson
        try {
            check(FoxholeNativeEngine.nativeAbiVersion() == 1)
            val capabilities = Json.parseToJsonElement(FoxholeNativeEngine.nativeCapabilities()).jsonObject
            val compiled = capabilities.getValue("protocols").jsonArray.mapNotNull { item ->
                item.jsonObject.takeIf { it["compiled"]?.jsonPrimitive?.booleanOrNull == true }
                    ?.get("id")?.jsonPrimitive?.content
            }.toSet()
            check(config.protocols.all(compiled::contains))
        } catch (_: LinkageError) { throw EngineException(EngineFailure.NATIVE_UNAVAILABLE) }
        catch (_: Exception) { throw EngineException(EngineFailure.NATIVE_UNAVAILABLE) }
        val underlying = underlyingNetwork() ?: throw EngineException(EngineFailure.START)
        val previousVpn = vpnNetworks().map { it.networkHandle }.toSet()
        val plan = config.tun
        val builder = service.Builder().setSession("FoxHole VPN").setMtu(plan.mtu)
            .setUnderlyingNetworks(arrayOf(underlying))
            .addAddress(plan.ipv4Address, plan.ipv4PrefixLength)
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(connectivity.getNetworkCapabilities(underlying)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
        }
        val ipv6Address = plan.ipv6Address
        val ipv6PrefixLength = plan.ipv6PrefixLength
        if (ipv6Address != null && ipv6PrefixLength != null) builder.addAddress(ipv6Address, ipv6PrefixLength)
        plan.routes.forEach { builder.addRoute(it.address, it.prefixLength) }
        plan.advertisedDnsServers.forEach(builder::addDnsServer)
        val applications = applicationRoutingPlan(routing.applicationScope, service.packageName)
        applications.allowed.forEach { name ->
            try { builder.addAllowedApplication(name) }
            catch (_: PackageManager.NameNotFoundException) { if (name == service.packageName) throw EngineException(EngineFailure.START) }
        }
        applications.disallowed.forEach { name ->
            try { builder.addDisallowedApplication(name) } catch (_: PackageManager.NameNotFoundException) { }
        }
        current()
        val advertisedDns = plan.advertisedDnsServers.map(::numericAddress).toSet()
        val tun = builder.establish() ?: throw EngineException(EngineFailure.START)
        pendingTun.set(tun)
        hooks?.onTun?.invoke(tun)
        var session: NativeFrame? = null
        var delivered = false
        try {
            current()
            val tunInterface = interfaceOf(tun)
            val nativeFd = AtomicInteger(-1)
            val continuationStart = AtomicReference<DnsContinuationLease.Start?>(null)
            val nativeCompletion = CompletableDeferred<Unit>()
            hooks?.onNativeStart?.invoke(nativeCompletion)
            val handle = try {
                nativeCall(20_000, onNotDispatched = { nativeCompletion.complete(Unit) }, onLate = { late ->
                    try {
                        if (hooks?.dnsContinuation != null && dnsContinuationStartUnsettled(late)) hooks.onUnsettledNative()
                        if (late > 0) {
                            val stopped = FoxholeNativeEngine.nativeStop(late)
                            val clean = if (hooks?.dnsContinuation != null) dnsContinuationStopClean(stopped) else stopped in releasedCodes
                            if (!clean) hooks?.onUnsettledNative?.invoke()
                        }
                    } catch (_: Throwable) { hooks?.onUnsettledNative?.invoke()
                    } finally {
                        continuationStart.getAndSet(null)?.finished()
                        nativeCompletion.complete(Unit)
                    }
                }) {
                    try {
                        // No detached FD or token borrow exists while a cancelled start is still queued.
                        val continuation = hooks?.dnsContinuation?.let { lease -> current(); lease.beginStart() }
                        continuationStart.set(continuation)
                        val duplicate = ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
                        nativeFd.set(duplicate)
                        if (continuation == null) {
                            FoxholeNativeEngine.nativeStartWithNetwork(duplicate, engineJson, underlying.networkHandle, service)
                        } else {
                            // Both current driver start paths have no DNS-rule-set bootstrap bundle.
                            FoxholeNativeEngine.nativeStartOnNetworkWithDnsContinuity(duplicate, engineJson,
                                underlying.networkHandle, continuation.token, null, null, null, null, false, service)
                        }
                    } catch (failure: Throwable) {
                        continuationStart.getAndSet(null)?.finished()
                        nativeCompletion.complete(Unit)
                        throw failure
                    }
                }.also {
                    continuationStart.getAndSet(null)?.finished()
                    nativeCompletion.complete(Unit)
                }
            } catch (_: TimeoutCancellationException) {
                throw EngineException(EngineFailure.START, processPoisoned = true)
            }
            if (hooks?.dnsContinuation != null && dnsContinuationStartUnsettled(handle)) {
                hooks.onUnsettledNative()
                throw EngineException(EngineFailure.START, processPoisoned = true)
            }
            if (handle <= 0) throw EngineException(EngineFailure.START)
            val captured = NativeFrame(handle, tun, nativeFd.get(), policy, underlying.networkHandle, config.tun,
                routing.webRules.isNotEmpty() || routing.blockedApplications.isNotEmpty(), preparation.managedPacketDns, preparation.dnsProofPlan)
            session = captured
            hooks?.onFrame?.invoke(captured)
            current()
            captured.network = awaitAccessibleVpnNetwork(
                candidate = {
                    if (!tun.fileDescriptor.valid() || VpnService.prepare(service) != null) throw EngineException(EngineFailure.START)
                    findOwnedVpnNetwork(previousVpn, tunInterface, advertisedDns, ownedHandover = hooks != null)
                },
                accessible = { network ->
                    // Owner visibility can precede netd UID routing permission after establish().
                    try { network.socketFactory.createSocket().use { true } } catch (_: IOException) { false }
                },
            ) ?: throw EngineException(EngineFailure.VALIDATION)
            current()
            pendingTun.compareAndSet(tun, null)
            delivered = true
            captured
        } catch (_: LinkageError) {
            throw EngineException(EngineFailure.NATIVE_UNAVAILABLE)
        } finally {
            if (!delivered) {
                // Target establish already deactivated the old gate. Rearm before closing any target descriptor.
                hooks?.beforeClose?.invoke()
                pendingTun.compareAndSet(tun, null)
                val stopped = withContext(NonCancellable) {
                    try { session?.stop() } catch (_: Exception) { EngineStopResult(false, poisoned = true) }
                }
                runCatching { tun.close() }
                if (stopped != null && !stopped.released) throw EngineException(EngineFailure.STOP, processPoisoned = hooks == null)
            }
        }
    }

    private class FramePreparation(val config: com.foxhole.core.runtime.PreparedVpnConfig,
        val policy: VpnNativeApplicationPolicy, val engineJson: String, val managedPacketDns: Boolean,
        val dnsProofPlan: WireGuardDnsProofPlan?)

    private fun prepareFrame(profile: VpnProfile, routing: VpnRoutingPolicy, blocked: Boolean): FramePreparation {
        val config = try { configurationForProfile(profile) } catch (_: IllegalArgumentException) {
            throw EngineException(EngineFailure.PROFILE)
        } catch (_: IllegalStateException) { throw EngineException(EngineFailure.PROFILE) }
        validateVpnRoutingPlatform(Build.VERSION.SDK_INT, config.protocols, routing)
        val policy = VpnNativeApplicationPolicy(config.engineJson, routing.webRules, routing.blockedApplications)
        val engine = policy.engine(blocked)
        val dnsProofPlan = if (config.protocols == setOf("wireguard") &&
            (routing.webRules.isNotEmpty() || routing.blockedApplications.isNotEmpty())) {
            try { wireGuardDnsProofPlan(config.protocols, engine, config.tun, routing, service.packageName, localInterfaceAddresses()) }
            catch (_: IllegalArgumentException) { throw EngineException(EngineFailure.PROFILE) }
        } else null
        return FramePreparation(config, policy, engine, managedWireGuardPacketDns(config.protocols, engine), dnsProofPlan)
    }

    private fun localInterfaceAddresses(): Set<InetAddress> {
        val interfaces = requireNotNull(java.net.NetworkInterface.getNetworkInterfaces()) { "Local interfaces unavailable" }
        val addresses = interfaces.toList().flatMap { it.inetAddresses.toList() } +
            connectivity.allNetworks.flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().map { link -> link.address } }
        return addresses.toSet().also { require(it.isNotEmpty()) { "Local interface addresses unavailable" } }
    }

    override fun abortPendingTun() { pendingTun.getAndSet(null)?.let { runCatching { it.close() } } }

    private fun underlyingNetwork(): Network? {
        val default = connectivity.activeNetwork
        val networks = (listOfNotNull(default) + connectivity.allNetworks).distinctBy { it.networkHandle }
        val candidates = networks.mapNotNull(::networkCandidate)
        val selected = preferredUnderlyingNetwork(default?.networkHandle, candidates)
        return networks.firstOrNull { it.networkHandle == selected }
    }

    private fun vpnNetworks(): List<Network> = connectivity.allNetworks.filter { network ->
        networkCandidate(network)?.let { isOwnedVpnNetwork(it, service.applicationInfo.uid, Build.VERSION.SDK_INT) } == true
    }

    private fun interfaceOf(descriptor: ParcelFileDescriptor): String? = try {
        File("/proc/self/fdinfo/${descriptor.fd}").reader().use { reader ->
            val buffer = CharArray(4096)
            val count = reader.read(buffer)
            if (count > 0) tunInterfaceName(String(buffer, 0, count)) else null
        }
    } catch (_: IOException) { null } catch (_: SecurityException) { null }

    private fun numericAddress(address: String): InetAddress = if (Build.VERSION.SDK_INT >= 29) {
        InetAddresses.parseNumericAddress(address)
    } else {
        // The translator admits numeric DNS addresses only; this never resolves a hostname.
        InetAddress.getByName(address)
    }

    private fun findOwnedVpnNetwork(previous: Set<Long>, tunInterface: String?, expectedDns: Set<InetAddress>,
        ownedHandover: Boolean = false): Network? {
        // A socket can bind to the retiring agent before Android publishes the new TUN's link properties.
        val networks = connectivity.allNetworks.filter { network ->
            val link = connectivity.getLinkProperties(network)
            matchesTunNetwork(tunInterface, link?.interfaceName, expectedDns, link?.dnsServers.orEmpty())
        }
        val selected = ownedVpnNetwork(
            networks.mapNotNull(::networkCandidate), service.applicationInfo.uid, Build.VERSION.SDK_INT, previous,
            freshOwnedTun = ownedHandover && VpnService.prepare(service) == null,
        )
        return networks.firstOrNull { it.networkHandle == selected }
    }

    private fun networkCandidate(network: Network): EngineNetworkCandidate? {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return EngineNetworkCandidate(
            handle = network.networkHandle,
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            unrestricted = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ownerUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capabilities.ownerUid else null,
        )
    }

    private inner class HandoverHooks(
        val isCurrent: () -> Boolean,
        val onTun: (ParcelFileDescriptor) -> Unit,
        val onFrame: (NativeFrame) -> Unit,
        val beforeClose: suspend () -> Unit,
        val onNativeStart: (CompletableDeferred<Unit>) -> Unit,
        val onUnsettledNative: () -> Unit,
        val dnsContinuation: DnsContinuationLease? = null,
    )

    private inner class ManagedSession(private val profile: VpnProfile, initial: NativeFrame) : EngineSession {
        private val frame = AtomicReference<NativeFrame?>(initial)
        private val target = AtomicReference<NativeFrame?>(null)
        private val targetTun = AtomicReference<ParcelFileDescriptor?>(null)
        private val barrier = AtomicReference<ParcelFileDescriptor?>(null)
        private val barrierActive = AtomicBoolean(false)
        private val closing = AtomicBoolean(false)
        private val policyLock = Mutex()
        private val opening = AtomicReference<CompletableDeferred<Unit>?>(null)
        private val barrierOpening = AtomicReference<CompletableDeferred<Unit>?>(null)
        private val nativeOpening = AtomicReference<CompletableDeferred<Unit>?>(null)
        private val nativeSettled = AtomicBoolean(true)
        private val dnsContinuation = AtomicReference<DnsContinuationLease?>(null)
        override val ownedNetworkHandle: Long? get() = frame.get()?.ownedNetworkHandle

        override suspend fun validate(): EngineLocation? = checkNotNull(frame.get()).validate()
        override suspend fun refreshLocation(): EngineLocation = checkNotNull(frame.get()).refreshLocation()
        override suspend fun networkChanged() { frame.get()?.networkChanged() }
        override suspend fun checkHealth(): Boolean = checkNotNull(frame.get()).checkHealth()

        override suspend fun applyRoutingPolicy(routing: VpnRoutingPolicy, isCurrent: () -> Boolean,
            persist: suspend () -> Unit): EngineLocation? = withContext(Dispatchers.IO) { policyLock.withLock {
            val previous = checkNotNull(frame.get())
            fun owned(): Boolean = isCurrent() && !closing.get() && VpnService.prepare(service) == null
            check(owned())
            val preparation = prepareFrame(profile, routing, true)
            suspend fun fullBarrier() {
                check(owned()) { "Routing handover superseded" }
                if (barrierActive.get() && barrier.get()?.fileDescriptor?.valid() == true) return
                val completion = CompletableDeferred<Unit>()
                barrierOpening.set(completion)
                try {
                check(owned()) { "Routing handover superseded" }
                val plan = previous.plan
                val previousNetworks = vpnNetworks().map { it.networkHandle }.toSet()
                val descriptor = service.Builder().setSession("FoxHole VPN").setMtu(plan.mtu)
                    .addAddress(plan.ipv4Address, plan.ipv4PrefixLength).addRoute("0.0.0.0", 0)
                    .addAddress(plan.ipv6Address ?: "fdfe:dcba:9876::1", plan.ipv6PrefixLength ?: 128).addRoute("::", 0)
                    .addDnsServer("192.0.2.53").setUnderlyingNetworks(emptyArray()).establish()
                    ?: throw EngineException(EngineFailure.START)
                barrier.getAndSet(descriptor)?.let { runCatching { it.close() } }
                val barrierInterface = interfaceOf(descriptor)
                val barrierDns = setOf(numericAddress("192.0.2.53"))
                val available = awaitAccessibleVpnNetwork(candidate = {
                    check(owned() && descriptor.fileDescriptor.valid()) { "Routing barrier owner superseded" }
                    findOwnedVpnNetwork(previousNetworks, barrierInterface, barrierDns, ownedHandover = true)
                }, accessible = { network ->
                    try { network.socketFactory.createSocket().use { true } } catch (_: IOException) { false }
                })
                check(available != null) { "Routing barrier network unavailable" }
                barrierActive.set(true)
                if (!owned()) {
                    barrier.compareAndSet(descriptor, null)
                    barrierActive.set(false)
                    descriptor.close()
                    throw IllegalStateException("Routing handover superseded")
                }
                } finally { completion.complete(Unit); barrierOpening.compareAndSet(completion, null) }
            }
            suspend fun retireTarget() {
                target.get()?.let { candidate ->
                    val result = candidate.stop()
                    check(result.released) { "Target native routing owner did not settle" }
                    target.compareAndSet(candidate, null)
                }
                targetTun.getAndSet(null)?.let { descriptor ->
                    pendingTun.compareAndSet(descriptor, null)
                    runCatching { descriptor.close() }
                }
            }
            val continuation = if (previous.managedPacketDns && preparation.managedPacketDns) {
                DnsContinuationLease { token ->
                    try { FoxholeNativeEngine.nativeReleaseDnsContinuation(token) }
                    catch (_: Exception) { nativeSettled.set(false) }
                    catch (_: LinkageError) { nativeSettled.set(false) }
                }.also { check(dnsContinuation.compareAndSet(null, it)) { "DNS continuation owner already exists" } }
            } else null
            val transaction = OsApplicationRoutingTransaction(::owned,
                holdNative = { previous.setBlocked(true) },
                establishBarrier = ::fullBarrier,
                stopPrevious = {
                    continuation?.let { lease ->
                        val captured = CompletableDeferred<Unit>()
                        nativeOpening.set(captured)
                        previous.retainDnsContinuation(lease, captured, ::owned)
                    }
                    check(owned())
                    val result = previous.stop()
                    check(result.released) { "Previous native routing owner did not settle" }
                    continuation?.let { lease ->
                        check(previous.cleanDnsContinuationStop) { "DNS continuation requires a clean joined stop" }
                        lease.oldStoppedCleanly()
                    }
                },
                establishTarget = {
                    val completion = CompletableDeferred<Unit>()
                    opening.set(completion)
                    try {
                        val created = openFrame(profile, routing, true, HandoverHooks(::owned,
                            onTun = { descriptor ->
                                // Establish success is the OS scope commit; the old barrier FD is now inactive.
                                barrierActive.set(false)
                                targetTun.set(descriptor)
                            }, onFrame = { target.set(it) },
                            beforeClose = { if (owned()) fullBarrier() },
                            onNativeStart = { nativeOpening.set(it) },
                            onUnsettledNative = { nativeSettled.set(false) }, dnsContinuation = continuation), preparation)
                        target.set(created)
                        if (continuation != null) requireDnsContinuationSettled(nativeSettled.get())
                    } finally { completion.complete(Unit); opening.compareAndSet(completion, null) }
                },
                releaseAndValidate = {
                    val candidate = checkNotNull(target.get())
                    check(owned())
                    candidate.setBlocked(false)
                    check(owned())
                    val location = candidate.validate()
                    check(owned())
                    check(frame.compareAndSet(previous, candidate)) { "Routing frame owner superseded" }
                    target.compareAndSet(candidate, null)
                    targetTun.set(null)
                    barrier.getAndSet(null)?.let { runCatching { it.close() } }
                    barrierActive.set(false)
                    location
                }, retireTarget = ::retireTarget,
            )
            try { transaction.apply(persist) }
            finally {
                continuation?.let { dnsContinuation.compareAndSet(it, null); it.close() }
            }
        } }

        override suspend fun stop(): EngineStopResult = withContext(NonCancellable) {
            closing.set(true)
            dnsContinuation.getAndSet(null)?.close()
            barrierActive.set(false)
            barrier.getAndSet(null)?.let { runCatching { it.close() } }
            targetTun.getAndSet(null)?.let { descriptor -> pendingTun.compareAndSet(descriptor, null); runCatching { descriptor.close() } }
            val frames = listOfNotNull(frame.getAndSet(null), target.getAndSet(null)).distinct()
            val results = frames.map { it.stop() }
            val settled = withTimeoutOrNull(5_000) {
                barrierOpening.get()?.await(); opening.get()?.await(); nativeOpening.get()?.await(); true
            } == true && nativeSettled.get()
            // A cancelled native start can finish while Stop waits. Drain any frame published by that late completion too.
            val lateResults = listOfNotNull(frame.getAndSet(null), target.getAndSet(null)).distinct().map { it.stop() }
            targetTun.getAndSet(null)?.let { descriptor -> pendingTun.compareAndSet(descriptor, null); runCatching { descriptor.close() } }
            barrier.getAndSet(null)?.let { runCatching { it.close() } }
            val all = results + lateResults
            EngineStopResult(settled && all.all { it.released }, !settled || all.any { it.poisoned })
        }
    }

    private inner class NativeFrame(
        private val handle: Long,
        private val tun: ParcelFileDescriptor,
        private val nativeFd: Int,
        private val policy: VpnNativeApplicationPolicy,
        initialUnderlyingHandle: Long,
        val plan: FoxCoreTunPlan,
        private val websitePolicy: Boolean,
        val managedPacketDns: Boolean,
        private val dnsProofPlan: WireGuardDnsProofPlan?,
    ) : EngineSession {
        var network: Network? = null
        override val ownedNetworkHandle: Long? get() = network?.networkHandle
        @Volatile private var released = false
        @Volatile private var closing = false
        @Volatile private var underlyingHandle = initialUnderlyingHandle
        private val stopLock = Mutex()
        @Volatile private var cleanJoinedStop = false
        @Volatile private var proofCleanupUnsettled = false
        val cleanDnsContinuationStop: Boolean get() = released && cleanJoinedStop && !proofCleanupUnsettled

        suspend fun retainDnsContinuation(lease: DnsContinuationLease, completion: CompletableDeferred<Unit>, isCurrent: () -> Boolean) {
            if (!managedPacketDns || released || closing || !isCurrent()) {
                completion.complete(Unit)
                error("Current managed WG DNS owner required")
            }
            val token = nativeCall(1_500, onNotDispatched = { completion.complete(Unit) }, onLate = { late ->
                try { if (late > 0) lease.captured(late) } finally { completion.complete(Unit) }
            }) {
                try {
                    check(isCurrent() && !released && !closing) { "DNS continuation owner superseded" }
                    FoxholeNativeEngine.nativeRetainDnsContinuation(handle)
                }
                catch (failure: Throwable) { completion.complete(Unit); throw failure }
            }
            try { lease.captured(token) } finally { completion.complete(Unit) }
        }
        private var proof: VpnNetworkProof? = null
        private var primaryProof: VpnProofProxy? = null
        private val packetProof = AtomicReference<WireGuardDnsProof?>(null)
        private var routeStamp: String? = null
        private var healthSamples = 0

        suspend fun setBlocked(blocked: Boolean) {
            check(!released && !closing)
            val raw = nativeCall(1_500) { FoxholeNativeEngine.nativeStats(handle) }
            check(raw.length <= 64 * 1024)
            val revision = requireNotNull(Json.parseToJsonElement(raw).jsonObject["policy_revision"]?.jsonPrimitive?.longOrNull)
            check(revision > 0)
            val next = nativeCall(3_000) {
                FoxholeNativeEngine.nativeReloadPolicy(handle, policy.policy(blocked, revision))
            }
            check(next > revision) { "Native routing barrier rejected" }
        }

        override suspend fun validate(): EngineLocation? {
            val owned = network ?: throw ValidationException(ValidationFailure.NETWORK_MISSING)
            if (!tun.fileDescriptor.valid()) throw ValidationException(ValidationFailure.TUN_CLOSED)
            if (owned !in vpnNetworks()) throw ValidationException(ValidationFailure.NETWORK_MISSING)
            proof?.close()
            if (dnsProofPlan != null) return withContext(Dispatchers.IO) {
                proof = null
                val before = currentRouteStamp()
                val context = dnsProofContext(owned)
                val verifier = WireGuardDnsProof(open = { transport ->
                    when (transport) {
                        DnsProofTransport.UDP -> udpDnsProofConnection(dnsProofPlan) { socket ->
                            dnsProofContext(owned); owned.bindSocket(socket)
                        }
                        DnsProofTransport.TCP -> tcpDnsProofConnection(dnsProofPlan) { socket ->
                            dnsProofContext(owned); owned.bindSocket(socket)
                        }
                    }
                })
                packetProof.getAndSet(verifier)?.closeAndJoin()
                try {
                    dnsProofContext(owned)
                    verifier.validate()
                    val after = currentRouteStamp()
                    if (before != after || context != dnsProofContext(owned))
                        throw ValidationException(ValidationFailure.ROUTE_CHANGED)
                    routeStamp = after
                    // This proof measures authenticated profile DNS, not an exit-IP service.
                    return@withContext null
                } catch (failure: Throwable) {
                    packetProof.compareAndSet(verifier, null)
                    verifier.closeAndJoin()
                    throw failure
                }
            }
            if (websitePolicy && primaryProof == null) {
                val request = VpnProofProxy.create()
                check(nativeCall(1_500) { FoxholeNativeEngine.nativeStartLoopbackInbound(handle, request.config()) } == 0) {
                    "Primary connection proof unavailable"
                }
                primaryProof = requireNotNull(VpnProofProxy.ready(
                    nativeCall(1_500) { FoxholeNativeEngine.nativeLoopbackInbounds(handle) }, request.password))
            }
            val verifier = VpnNetworkProof(owned, primaryProof)
            proof = verifier
            val before = currentRouteStamp()
            verifier.validate()
            val location = try { verifier.location() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val after = currentRouteStamp()
            if (before != after) throw ValidationException(ValidationFailure.ROUTE_CHANGED)
            routeStamp = after
            return location
        }

        override suspend fun refreshLocation(): EngineLocation {
            if (dnsProofPlan != null) throw EngineException(EngineFailure.GEO)
            val before = currentRouteStamp()
            val location = (proof ?: throw EngineException(EngineFailure.GEO)).location()
            val after = currentRouteStamp()
            if (before != after) throw EngineException(EngineFailure.GEO)
            routeStamp = after
            return location
        }

        override suspend fun checkHealth(): Boolean {
            if (!tun.fileDescriptor.valid() || network?.let { it in vpnNetworks() } != true) throw EngineException(EngineFailure.VALIDATION)
            val current = currentRouteStamp()
            if (++healthSamples % 6 == 0) {
                if (dnsProofPlan != null) withContext(Dispatchers.IO) {
                    val owned = network ?: throw EngineException(EngineFailure.VALIDATION)
                    val before = dnsProofContext(owned)
                    (packetProof.get() ?: throw EngineException(EngineFailure.VALIDATION)).validate()
                    if (before != dnsProofContext(owned)) throw ValidationException(ValidationFailure.ROUTE_CHANGED)
                } else (proof ?: throw EngineException(EngineFailure.VALIDATION)).validate()
            }
            return current != routeStamp
        }

        private fun dnsProofContext(owned: Network): DnsProofContextStamp {
            val resolverPlan = checkNotNull(dnsProofPlan)
            if (closing || released || !tun.fileDescriptor.valid()) throw CancellationException("DNS proof owner closed")
            if (network != owned || owned !in vpnNetworks()) throw ValidationException(ValidationFailure.NETWORK_MISSING)
            val links = connectivity.getLinkProperties(owned) ?: throw ValidationException(ValidationFailure.NETWORK_MISSING)
            val tunInterface = interfaceOf(tun) ?: throw ValidationException(ValidationFailure.NETWORK_MISSING)
            val observedUnderlying = underlyingNetwork()?.networkHandle ?: throw ValidationException(ValidationFailure.NETWORK_MISSING)
            val routes = links.routes.map { route ->
                val ownedInterface = route.`interface` == links.interfaceName
                val unicast = ownedInterface && (if (Build.VERSION.SDK_INT >= 33) route.type == android.net.RouteInfo.RTN_UNICAST
                else {
                    // Before the type getter exists, only our unicast Builder.addRoute entries are known.
                    // An unknown more-specific live route cannot inherit a default route's authority.
                    plan.routes.any { configured -> configured.prefixLength == route.destination.prefixLength &&
                        proofNumericAddress(configured.address) == route.destination.address }
                })
                DnsProofLiveRoute(route.destination.address, route.destination.prefixLength, unicast)
            }
            return try {
                wireGuardDnsLiveContext(resolverPlan, owned.networkHandle, observedUnderlying, tunInterface,
                    links.interfaceName, links.dnsServers, routes, localInterfaceAddresses())
            } catch (_: IllegalArgumentException) { throw ValidationException(ValidationFailure.DNS) }
        }

        private suspend fun currentRouteStamp(): String {
            val raw = nativeCall(1_500) { FoxholeNativeEngine.nativeStats(handle) }
            if (raw.length > 64 * 1024) throw ValidationException(ValidationFailure.NATIVE_STATS_OVERSIZED)
            val stats = try { Json.parseToJsonElement(raw).jsonObject }
            catch (_: IllegalArgumentException) { throw ValidationException(ValidationFailure.NATIVE_STATS_MALFORMED) }
            val generation = try { stats["generation"]?.jsonPrimitive?.longOrNull ?: 0 }
            catch (_: IllegalArgumentException) { throw ValidationException(ValidationFailure.NATIVE_STATS_MALFORMED) }
            if (generation <= 0) throw ValidationException(ValidationFailure.NATIVE_GENERATION_MISSING)
            return "$generation:${stats["selectors"] ?: "[]"}"
        }

        override suspend fun networkChanged() {
            val underlying = underlyingNetwork() ?: throw EngineException(EngineFailure.VALIDATION)
            service.setUnderlyingNetworks(arrayOf(underlying))
            nativeCall(1_500) { FoxholeNativeEngine.nativeNetworkChangedWithHandle(handle, underlying.networkHandle) }
            underlyingHandle = underlying.networkHandle
        }

        override suspend fun stop(): EngineStopResult = withContext(NonCancellable) { stopLock.withLock {
            closing = true
            try { packetProof.getAndSet(null)?.closeAndJoin() }
            catch (_: Throwable) {
                proofCleanupUnsettled = true
                cleanJoinedStop = false
            }
            proof?.close()
            runCatching { tun.close() }
            if (!released) {
                val graceful = withTimeoutOrNull(3_750) { nativeCall(3_500) { FoxholeNativeEngine.nativeStop(handle) } }
                if (dnsContinuationStopClean(graceful) && !proofCleanupUnsettled) cleanJoinedStop = true
                if (graceful !in releasedCodes) {
                    val forced = withTimeoutOrNull(1_750) { nativeCall(1_500) { FoxholeNativeEngine.nativeForceKill(handle) } }
                    if (forced !in releasedCodes) return@withLock EngineStopResult(false, poisoned = true)
                }
                if (runCatching { Os.readlink("/proc/self/fd/$nativeFd").startsWith("/dev/tun") }.getOrDefault(false)) {
                    return@withLock EngineStopResult(false, poisoned = true)
                }
                released = true
            }
            // A replacement can reuse the NetworkAgent. Descriptor ownership is the teardown proof.
            EngineStopResult(released && !tun.fileDescriptor.valid() && !proofCleanupUnsettled,
                poisoned = proofCleanupUnsettled)
        } }
    }

    private companion object {
        val releasedCodes = setOf(FoxholeNativeEngine.STOPPED, FoxholeNativeEngine.ALREADY_STOPPED, FoxholeNativeEngine.STOP_UNKNOWN_HANDLE)
    }
}

private val nativeExecutor = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "foxhole-vpn-native").apply { isDaemon = true }
}

private suspend fun <T> nativeCall(timeoutMs: Long, onLate: (T) -> Unit = {}, onNotDispatched: () -> Unit = {}, operation: () -> T): T {
    val dispatched = AtomicBoolean(false)
    try {
        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                dispatched.set(true)
                try {
                    nativeExecutor.execute {
                        try {
                            val value = operation()
                            continuation.resume(value, onCancellation = { _, discarded, _ -> onLate(discarded) })
                        } catch (failure: Exception) {
                            continuation.resumeWithException(failure)
                        } catch (failure: LinkageError) {
                            continuation.resumeWithException(failure)
                        }
                    }
                } catch (failure: RejectedExecutionException) {
                    dispatched.set(false)
                    throw failure
                }
            }
        }
    } finally { if (!dispatched.get()) onNotDispatched() }
}
