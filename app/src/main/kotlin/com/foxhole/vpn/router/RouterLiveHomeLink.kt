package com.foxhole.vpn.router

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom

internal class RouterLiveHomeLink(
    private val manager: ConnectivityManager,
    private val transport: RouterWizardTransport,
    private val pairingStore: RouterPairingStore,
    private val provisioner: RouterHttpProvisioner,
    private val protection: RouterSocketProtection,
    private val access: () -> Unit,
    private val phone: RouterHomePhoneController,
    private val elapsedMs: () -> Long,
) : RouterHomeLink {
    private var pairing: RouterPairing? = null
    private var api: RouterPanelApi? = null
    private var session: RouterPanelSession? = null
    private var authenticatedNetwork: Long? = null
    private var authenticatedAddress: String? = null
    private val clock = RouterAuthenticatedClock()
    private var providerResolve = false
    private val operations = Mutex()
    private val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var activeClaim: String? = null
    @Volatile private var activePlan: RouterBoundDialPlan? = null
    private var validatedCandidate: RouterValidatedDialMemo? = null
    @Volatile private var closed = false
    override val pairingId: String get() = requireNotNull(pairing).id
    override val hasPhoneLease: Boolean get() = activePlan != null
    override val phoneLeaseOwner: RouterPhoneSession? get() = activePlan?.owner
    override val phoneLeaseDeadline: Long? get() = activePlan?.expiresAtElapsedMs

    override val changes: Flow<Unit> = callbackFlow {
        val gate = RouterWifiEventGate()
        fun changed(network: Network, capabilities: NetworkCapabilities?, properties: LinkProperties?) {
            val observation = RouterWifiObservation(
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                properties?.interfaceName,
                properties?.linkAddresses.orEmpty().map { "${it.address.hostAddress}/${it.prefixLength}" }.toSet(),
                properties?.routes.orEmpty().map { "${it.destination}:${it.gateway?.hostAddress}:${it.isDefaultRoute}:${if (Build.VERSION.SDK_INT >= 33) it.type else 0}" }.toSet(),
            )
            if (gate.changed(network.networkHandle, observation)) trySend(Unit)
        }
        // Callback snapshots avoid racing synchronous connectivity re-queries.
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val capabilities = mutableMapOf<Network, NetworkCapabilities>()
            private val properties = mutableMapOf<Network, LinkProperties>()
            override fun onAvailable(network: Network) {
                capabilities.remove(network); properties.remove(network)
                changed(network, null, null)
            }
            override fun onLost(network: Network) {
                capabilities.remove(network); properties.remove(network)
                if (gate.lost(network.networkHandle)) trySend(Unit)
            }
            override fun onCapabilitiesChanged(network: Network, current: NetworkCapabilities) {
                capabilities[network] = current
                changed(network, current, properties[network])
            }
            override fun onLinkPropertiesChanged(network: Network, current: LinkProperties) {
                properties[network] = current
                changed(network, capabilities[network], current)
            }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    override suspend fun initialize() = operations.withLock {
        check(!closed)
        if (pairing == null) pairing = pairingStore.read() ?: throw RouterSetupFailure(RouterWizardError.PIN)
        else verifyPairing()
        ensureConnection()
        val status = freshStatus()
        if (status.lanDeviceId == null || !status.handoffKnown) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
    }

    override fun wifiIsPaired(): Boolean {
        check(!closed)
        val identity = pairing?.identity ?: return false
        return try { wifi(identity); true } catch (_: RouterWifiRequired) { false }
    }

    override suspend fun status(): RouterPhoneStatus = operations.withLock {
        check(!closed)
        verifyPairing()
        ensureConnection()
        freshStatus()
    }

    override suspend fun validatePhoneResume(owner: RouterPhoneSession) = operations.withLock {
        val selected = wifi(requireNotNull(pairing).identity)
        if (validatedCandidate?.current(owner, selected.network.networkHandle, requireNotNull(selected.localAddress.hostAddress), elapsedMs()) == true) return@withLock
        val prepared = candidate(owner)
        currentCoroutineContext().ensureActive()
        check(!closed)
        validatedCandidate = prepared
    }

    override suspend fun leaseForPhone(owner: RouterPhoneSession): RouterBoundDialPlan = operations.withLock {
        check(!closed)
        val selected = wifi(requireNotNull(pairing).identity)
        val candidate = validatedCandidate?.takeIf { it.current(owner, selected.network.networkHandle, requireNotNull(selected.localAddress.hostAddress), elapsedMs()) }
            ?.candidate ?: candidate(owner).candidate
        val claim = routerLeaseClaim(random, candidate)
        val requestAt = elapsedMs()
        val result = handoff(buildJsonObject {
            put("claimId", claim); put("mode", "phone_vpn"); put("leaseMs", 60000)
            put("upstreamTargets", JsonArray(candidate.targets.map { target -> buildJsonObject {
                put("address", target.address); put("port", target.port); put("transport", target.transport)
            } }))
        })
        val received = elapsedMs()
        val lease = parseRouterHandoff(result, claim, "phone_vpn", received, received - requestAt)
        if (lease.phase != "active" || lease.expiresAtElapsedMs <= received) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        clock.observe(lease.observedUptimeMs, requestAt, received)
        currentCoroutineContext().ensureActive()
        check(!closed)
        activeClaim = claim
        candidate.leased(claim, lease.expiresAtElapsedMs).also { activePlan = it }
    }

    override suspend fun renewPhoneLease(owner: RouterPhoneSession, stillOwned: () -> Boolean) = operations.withLock {
        if (closed || !stillOwned()) return@withLock
        val plan = activePlan ?: return@withLock
        if (owner != plan.owner || elapsedMs() < plan.expiresAtElapsedMs - 30000) return@withLock
        val at = elapsedMs()
        val result = handoff(buildJsonObject {
            put("claimId", plan.claimId); put("mode", "phone_vpn"); put("leaseMs", 60000)
            put("upstreamTargets", JsonArray(plan.targets.map { target -> buildJsonObject {
                put("address", target.address); put("port", target.port); put("transport", target.transport)
            } }))
        })
        val received = elapsedMs()
        val renewed = parseRouterHandoff(result, plan.claimId, "phone_vpn", received, received - at)
        if (renewed.phase != "active" || renewed.expiresAtElapsedMs <= received) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        clock.observe(renewed.observedUptimeMs, at, received)
        currentCoroutineContext().ensureActive()
        if (closed || !stillOwned()) return@withLock
        activePlan = RouterBoundDialPlan(plan.owner, plan.profileSha256, plan.prepared, plan.targets, plan.claimId, renewed.expiresAtElapsedMs)
    }

    override suspend fun phoneStarted(claimId: String, owner: RouterPhoneSession) = operations.withLock {
        if (closed) return@withLock
        val plan = activePlan ?: return@withLock
        if (plan.claimId != claimId || plan.owner.profileId != owner.profileId || plan.owner.profileRevision != owner.profileRevision) return@withLock
        activePlan = RouterBoundDialPlan(owner, plan.profileSha256, plan.prepared, plan.targets, plan.claimId, plan.expiresAtElapsedMs)
    }

    override suspend fun routedAfterLeaseRelease(proof: RouterHomeProof, expectedOwner: RouterPhoneSession?, stillOwned: () -> Boolean): RouterHomeProof? = operations.withLock {
        if (closed || !routerLeaseReleaseOwned(activePlan?.owner, expectedOwner, stillOwned())) return@withLock null
        if (proof.pairingId != pairingId || elapsedMs() < proof.verifiedAtElapsedMs || elapsedMs() - proof.verifiedAtElapsedMs > 5000) return@withLock null
        verifyPairing()
        ensureConnection()
        val observed = freshStatus()
        if (!routerHandoffCanRelease(observed, activeClaim) || !stillOwned()) return@withLock null
        activeClaim?.let { claim ->
            val requestAt = elapsedMs()
            val result = handoff(buildJsonObject {
                put("claimId", claim); put("mode", "router_vpn"); put("leaseMs", 60000); put("upstreamTargets", JsonArray(emptyList()))
                put("expectedServerId", proof.serverId); put("expectedGeneration", proof.generation)
            })
            val received = elapsedMs()
            val released = parseRouterHandoff(result, claim, "router_vpn", received, received - requestAt, proof)
            if (released.phase != "released") return@withLock null
            clock.observe(released.observedUptimeMs, requestAt, received)
            activeClaim = null
            activePlan = null
        }
        currentCoroutineContext().ensureActive()
        if (closed || !stillOwned()) return@withLock null
        val after = freshStatus()
        after.proof.takeIf { after.handoffKnown && after.handoff == null }
    }

    private suspend fun candidate(owner: RouterPhoneSession): RouterValidatedDialMemo {
        val selected = wifi(requireNotNull(pairing).identity)
        var memoUntil = elapsedMs().let { if (it <= Long.MAX_VALUE - 300000) it + 300000 else Long.MAX_VALUE }
        val prepared = phone.dialCandidate(owner) { host ->
            selected.requireCurrent()
            val response = resolveProvider(host)
            selected.requireCurrent()
            memoUntil = minOf(memoUntil, response.expiresAtElapsedMs)
            response.addresses
        }
        selected.requireCurrent()
        val current = wifi(requireNotNull(pairing).identity)
        if (current.network.networkHandle != selected.network.networkHandle || current.localAddress != selected.localAddress) throw RouterWifiRequired()
        val at = elapsedMs()
        return RouterValidatedDialMemo(prepared, selected.network.networkHandle, requireNotNull(selected.localAddress.hostAddress), at,
            (memoUntil - at).coerceIn(0, 300000))
    }

    private suspend fun resolveProvider(host: String): RouterProviderResolution = boundedRequest {
        verifyPairing()
        ensureConnection()
        withSessionRetry {
            freshStatus()
            if (!providerResolve) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
            provisioner.resolveProvider(requireNotNull(api), requireNotNull(session), pairingId, host) {
                clock.estimate(elapsedMs()) ?: throw RouterApiFailure(RouterApiError.NETWORK)
            }
        }
    }

    private suspend fun freshStatus(): RouterPhoneStatus = withSessionRetry {
        provisioner.phoneStatus(requireNotNull(api), requireNotNull(session), pairingId).also(::observeStatusClock)
    }

    private fun observeStatusClock(status: RouterPhoneStatus) {
        val received = status.receivedAtElapsedMs ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val duration = status.requestDurationMs ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val uptime = status.observedUptimeMs ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        if (clock.observe(uptime, received - duration, received)) validatedCandidate = null
    }

    private suspend fun <T> boundedRequest(block: suspend () -> T): T = try { withTimeout(5000) { block() } }
    catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw RouterApiFailure(RouterApiError.NETWORK)
    }

    private suspend fun handoff(payload: JsonObject): JsonObject {
        verifyPairing()
        ensureConnection()
        val selected = wifi(requireNotNull(pairing).identity)
        return boundedRequest { withSessionRetry {
            selected.requireCurrent()
            if (clock.estimate(elapsedMs()) == null) freshStatus()
            val requestUptime = clock.estimate(elapsedMs()) ?: throw RouterApiFailure(RouterApiError.NETWORK)
            requireNotNull(api).mobileRequest(RouterMobileAction.PHONE_HANDOFF,
                JsonObject(payload + mapOf("token" to JsonPrimitive(requireNotNull(session).token()),
                    "address" to JsonPrimitive(requireNotNull(selected.localAddress.hostAddress)), "requestUptimeMs" to JsonPrimitive(requestUptime))))
        } }
    }

    private suspend fun ensureConnection() {
        check(!closed)
        val saved = requireNotNull(pairing)
        val selected = wifi(saved.identity)
        val handle = selected.network.networkHandle
        val address = selected.localAddress.hostAddress
        if (api != null && session != null && authenticatedNetwork == handle && authenticatedAddress == address) return
        api?.close(); api = null
        session?.close(); session = null
        authenticatedNetwork = null; authenticatedAddress = null
        clock.clear(); providerResolve = false; validatedCandidate = null
        val client = transport.api(saved.identity)
        var token: RouterPanelSession? = null
        try {
            token = client.unlockPairing(saved)
            val capabilities = provisioner.capabilities(client, token)
            if (!capabilities.homeProof || !capabilities.phoneHandoff) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
            val status = provisioner.phoneStatus(client, token, saved.id)
            if (!status.handoffKnown) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
            observeStatusClock(status)
            currentCoroutineContext().ensureActive()
            check(!closed)
            selected.requireCurrent()
            val current = wifi(saved.identity)
            if (current.network.networkHandle != handle || current.localAddress.hostAddress != address) throw RouterWifiRequired()
            api = client; session = token; authenticatedNetwork = handle; authenticatedAddress = address; providerResolve = capabilities.providerResolve
        } catch (failure: Exception) {
            token?.close(); client.close()
            if (failure is RouterApiFailure && failure.kind == RouterApiError.IDENTITY) throw RouterHomeNotPairedNetwork()
            throw failure
        }
    }

    private suspend fun <T> withSessionRetry(request: suspend () -> T): T {
        try { return request() }
        catch (failure: RouterApiFailure) {
            if (failure.kind != RouterApiError.PIN) throw failure
            session?.close(); session = null
            ensureConnection()
            return request()
        }
    }

    private suspend fun verifyPairing() {
        val expected = requireNotNull(pairing)
        val current = pairingStore.read() ?: throw RouterHomePairingChanged()
        try {
            if (current.id != expected.id || current.identity != expected.identity || !MessageDigest.isEqual(current.credential, expected.credential))
                throw RouterHomePairingChanged()
        } finally { current.clear() }
    }

    private fun wifi(identity: RouterTlsIdentity) = RouterWifiConnection.select(manager, identity.endpoint, protection, access).also { it.requireCurrent() }

    override fun close() {
        closed = true
        api?.close(); api = null
        session?.close(); session = null
        pairing?.clear(); pairing = null
        random.fill(0)
        activeClaim = null; activePlan = null; validatedCandidate = null
        authenticatedNetwork = null; authenticatedAddress = null
        clock.clear(); providerResolve = false
    }
}

internal data class RouterHandoffReceipt(val phase: String, val expiresAtElapsedMs: Long, val policyRevision: Long, val observedUptimeMs: Long)

internal fun parseRouterHandoff(value: JsonObject, claim: String, mode: String, now: Long, requestDuration: Long,
    expectedProof: RouterHomeProof? = null): RouterHandoffReceipt {
    fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    fun number(key: String) = (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    if (number("schema") != 1L || text("claimId") != claim || !claim.matches(Regex("[a-f0-9]{32}")) || text("mode") != mode ||
        mode !in setOf("phone_vpn", "router_vpn") || now < 0 || requestDuration !in 0..5000) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    val phase = text("phase")
    val remaining = number("remainingMs")
    val revision = number("policyRevision")
    if (phase !in setOf("active", "validating", "released") || remaining !in 0..60000 || revision < 0 || number("observedUptimeMs") !in 0..9007199254740991L ||
        mode == "phone_vpn" && phase != "active" || mode == "router_vpn" && phase == "active" || mode == "router_vpn" && remaining != 0L)
        throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    for ((key, pattern) in listOf("serverId" to Regex("server-[a-f0-9]{12}"), "generation" to Regex("[a-f0-9]{16}"))) {
        val item = value[key]
        if (item != JsonNull && (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.matches(pattern) != true)
            throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    }
    if (expectedProof != null && (mode != "router_vpn" || text("serverId") != expectedProof.serverId || text("generation") != expectedProof.generation))
        throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    val usable = (remaining - requestDuration).coerceAtLeast(0)
    if (now > Long.MAX_VALUE - usable) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    return RouterHandoffReceipt(phase, now + usable, revision, number("observedUptimeMs"))
}
