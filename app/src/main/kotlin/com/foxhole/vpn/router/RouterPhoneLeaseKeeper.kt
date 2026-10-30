package com.foxhole.vpn.router

import com.foxhole.vpn.VpnPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class RouterPhoneLeaseKeeper(
    private val scope: CoroutineScope,
    private val phone: RouterHomePhoneController,
    private val elapsedMs: () -> Long,
    private val localNetworkAccess: () -> Boolean,
) {
    private class Lease(val link: RouterHomeLink, val owner: RouterPhoneSession, val manualGeneration: Long, val createdAt: Long) {
        var job: Job? = null
        @Volatile var transferred = false
        @Volatile var pendingSince: Long? = createdAt
    }
    private val ownership = Any()
    @Volatile private var lease: Lease? = null
    val owner: RouterPhoneSession? get() = lease?.owner

    fun routerSelectionChanged() {
        val old = synchronized(ownership) { lease.also { lease = null } }
        old?.job?.cancel()
        old?.link?.close()
    }

    fun retain(link: RouterHomeLink, owner: RouterPhoneSession, manualGeneration: Long): Boolean {
        val accepted = Lease(link, owner, manualGeneration, elapsedMs())
        if (link.phoneLeaseOwner != owner || !currentPhone(accepted) || !localNetworkAccess() || (link.phoneLeaseDeadline ?: 0) <= elapsedMs()) return false
        val old = synchronized(ownership) { lease.also {
            if (it?.link === link) it.transferred = true
            lease = accepted
        } }
        old?.job?.cancel()
        if (old?.link !== link) old?.link?.close()
        accepted.job = scope.launch { maintain(accepted) }
        return true
    }

    fun borrow(): RouterHomeLink? {
        val current = lease ?: return null
        return current.link.takeIf { currentPhone(current) && localNetworkAccess() && pairedWifi(current) && (it.phoneLeaseDeadline ?: 0) > elapsedMs() }
    }

    fun owns(link: RouterHomeLink): Boolean = lease?.link === link

    fun adopt(link: RouterHomeLink): Boolean {
        val adopted = synchronized(ownership) {
            val current = lease?.takeIf { it.link === link } ?: return false
            if (!currentPhone(current) || !localNetworkAccess() || !pairedWifi(current) || (link.phoneLeaseDeadline ?: 0) <= elapsedMs()) return false
            current.transferred = true
            lease = null
            current
        }
        adopted.job?.cancel()
        return true
    }

    private fun currentPhone(current: Lease): Boolean {
        if (phone.manualGeneration.value != current.manualGeneration) return false
        val state = phone.snapshot()
        if (state.selectedProfileId != current.owner.profileId || state.profileRevision != current.owner.profileRevision) return false
        val elapsed = elapsedMs()
        return synchronized(current) {
            if (state.connected == current.owner) { current.pendingSince = null; true }
            else if (state.connected == null && state.pending == current.owner && !state.idle &&
                phone.changes.value.phase in setOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING)) {
                val since = current.pendingSince ?: elapsed.also { current.pendingSince = it }
                elapsed >= since && elapsed - since < 30000
            } else false
        }
    }

    private fun pairedWifi(current: Lease): Boolean = try { current.link.wifiIsPaired() } catch (_: Exception) { false }

    private fun stillOwned(current: Lease): Boolean = lease === current && !current.transferred &&
        currentPhone(current) && localNetworkAccess() && pairedWifi(current)

    private suspend fun maintain(current: Lease) = coroutineScope {
        val events = Channel<Unit>(Channel.CONFLATED)
        val states = launch { phone.changes.collect { events.trySend(Unit) } }
        val commands = launch { phone.manualGeneration.collect { events.trySend(Unit) } }
        val networks = launch { current.link.changes.collect { events.trySend(Unit) } }
        try {
            while (stillOwned(current)) {
                val deadline = current.link.phoneLeaseDeadline ?: break
                if (elapsedMs() >= deadline) break
                var waitMs = 20000L
                try {
                    current.link.renewPhoneLease(current.owner) { stillOwned(current) }
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (fatal: RouterHomePairingChanged) { break }
                catch (failure: RouterApiFailure) {
                    if (failure.kind != RouterApiError.NETWORK) break
                    waitMs = 5000
                }
                catch (_: Exception) { waitMs = 5000 }
                val now = elapsedMs()
                waitMs = minOf(waitMs, ((current.link.phoneLeaseDeadline ?: deadline) - now).coerceAtLeast(1))
                current.pendingSince?.let { since -> waitMs = minOf(waitMs, (30000 - (now - since)).coerceAtLeast(1)) }
                withTimeoutOrNull(waitMs) { events.receive() }
            }
        } finally {
            states.cancel(); commands.cancel(); networks.cancel(); events.close()
            synchronized(ownership) { if (lease === current) lease = null }
            if (!current.transferred) current.link.close()
        }
    }
}
