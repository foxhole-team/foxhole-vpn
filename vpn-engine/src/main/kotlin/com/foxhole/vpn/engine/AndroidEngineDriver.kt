package com.foxhole.vpn.engine

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import com.foxhole.core.runtime.FoxholeNativeEngine
import com.foxhole.core.runtime.VpnEngineConfig
import com.foxhole.vpn.profile.VpnProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

class AndroidEngineDriver(private val service: VpnService,
    private val configurationForProfile: (VpnProfile) -> com.foxhole.core.runtime.PreparedVpnConfig = VpnEngineConfig::prepare) : EngineDriver {
    private val connectivity = requireNotNull(service.getSystemService(ConnectivityManager::class.java))
    private val pendingTun = AtomicReference<ParcelFileDescriptor?>(null)

    override suspend fun open(profile: VpnProfile): EngineSession = withContext(Dispatchers.IO) {
        if (VpnService.prepare(service) != null) throw EngineException(EngineFailure.START)
        val config = try { configurationForProfile(profile) } catch (_: IllegalArgumentException) {
            throw EngineException(EngineFailure.PROFILE)
        } catch (_: IllegalStateException) { throw EngineException(EngineFailure.PROFILE) }
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
        val tun = builder.establish() ?: throw EngineException(EngineFailure.START)
        pendingTun.set(tun)
        var session: NativeSession? = null
        var delivered = false
        try {
            val duplicate = ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
            val handle = try {
                nativeCall(20_000, onLate = { late -> if (late > 0) FoxholeNativeEngine.nativeStop(late) }) {
                    FoxholeNativeEngine.nativeStartWithNetwork(duplicate, config.engineJson, underlying.networkHandle, service)
                }
            } catch (_: TimeoutCancellationException) {
                throw EngineException(EngineFailure.START, processPoisoned = true)
            }
            if (handle <= 0) throw EngineException(EngineFailure.START)
            val captured = NativeSession(handle, tun, duplicate)
            session = captured
            captured.network = awaitAccessibleVpnNetwork(
                candidate = {
                    if (!tun.fileDescriptor.valid() || VpnService.prepare(service) != null) throw EngineException(EngineFailure.START)
                    findOwnedVpnNetwork(previousVpn)
                },
                accessible = { network ->
                    // Owner visibility can precede netd UID routing permission after establish().
                    try { network.socketFactory.createSocket().use { true } } catch (_: IOException) { false }
                },
            ) ?: throw EngineException(EngineFailure.VALIDATION)
            pendingTun.compareAndSet(tun, null)
            delivered = true
            captured
        } catch (_: LinkageError) {
            throw EngineException(EngineFailure.NATIVE_UNAVAILABLE)
        } finally {
            if (!delivered) {
                pendingTun.compareAndSet(tun, null)
                val stopped = withContext(NonCancellable) {
                    try { session?.stop() } catch (_: Exception) { EngineStopResult(false, poisoned = true) }
                }
                runCatching { tun.close() }
                if (stopped != null && !stopped.released) throw EngineException(EngineFailure.STOP, processPoisoned = true)
            }
        }
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

    private fun findOwnedVpnNetwork(previous: Set<Long>): Network? {
        val networks = connectivity.allNetworks.toList()
        val selected = ownedVpnNetwork(
            networks.mapNotNull(::networkCandidate), service.applicationInfo.uid, Build.VERSION.SDK_INT, previous,
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

    private inner class NativeSession(
        private val handle: Long,
        private val tun: ParcelFileDescriptor,
        private val nativeFd: Int,
    ) : EngineSession {
        var network: Network? = null
        override val ownedNetworkHandle: Long? get() = network?.networkHandle
        private var released = false
        private var proof: VpnNetworkProof? = null
        private var routeStamp: String? = null
        private var healthSamples = 0

        override suspend fun validate(): EngineLocation? {
            val owned = network ?: throw ValidationException(ValidationFailure.NETWORK_MISSING)
            if (!tun.fileDescriptor.valid()) throw ValidationException(ValidationFailure.TUN_CLOSED)
            if (owned !in vpnNetworks()) throw ValidationException(ValidationFailure.NETWORK_MISSING)
            proof?.close()
            val verifier = VpnNetworkProof(owned)
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
            if (++healthSamples % 6 == 0) (proof ?: throw EngineException(EngineFailure.VALIDATION)).validate()
            return current != routeStamp
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
        }

        override suspend fun stop(): EngineStopResult {
            proof?.close()
            runCatching { tun.close() }
            if (!released) {
                val graceful = withTimeoutOrNull(3_750) { nativeCall(3_500) { FoxholeNativeEngine.nativeStop(handle) } }
                if (graceful !in releasedCodes) {
                    val forced = withTimeoutOrNull(1_750) { nativeCall(1_500) { FoxholeNativeEngine.nativeForceKill(handle) } }
                    if (forced !in releasedCodes) return EngineStopResult(false, poisoned = true)
                }
                if (runCatching { Os.readlink("/proc/self/fd/$nativeFd").startsWith("/dev/tun") }.getOrDefault(false)) {
                    return EngineStopResult(false, poisoned = true)
                }
                released = true
            }
            val detached = withTimeoutOrNull(2_500) {
                while (network?.let { it in vpnNetworks() } == true) delay(50)
                true
            } == true
            return EngineStopResult(detached && !tun.fileDescriptor.valid())
        }
    }

    private companion object {
        val releasedCodes = setOf(FoxholeNativeEngine.STOPPED, FoxholeNativeEngine.ALREADY_STOPPED, FoxholeNativeEngine.STOP_UNKNOWN_HANDLE)
    }
}

private val nativeExecutor = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "foxhole-vpn-native").apply { isDaemon = true }
}

private suspend fun <T> nativeCall(timeoutMs: Long, onLate: (T) -> Unit = {}, operation: () -> T): T =
    withTimeout(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
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
        }
    }
