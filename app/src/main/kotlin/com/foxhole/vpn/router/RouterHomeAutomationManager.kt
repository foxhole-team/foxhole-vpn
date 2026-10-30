package com.foxhole.vpn.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

internal class RouterHomePairingChanged : IOException("router_home_pairing_changed")
internal class RouterHomeNotPairedNetwork : IOException("router_home_network_not_paired")

internal enum class RouterHomeMonitorState { OFF, MONITORING, ROUTER_UNAVAILABLE, PROFILE_UNSUPPORTED }

internal interface RouterHomeLink : AutoCloseable {
    val pairingId: String
    val changes: Flow<Unit>
    fun wifiIsPaired(): Boolean
    suspend fun initialize()
    suspend fun status(): RouterPhoneStatus
    suspend fun validatePhoneResume(owner: RouterPhoneSession)
    suspend fun routedAfterLeaseRelease(proof: RouterHomeProof, expectedOwner: RouterPhoneSession?, stillOwned: () -> Boolean): RouterHomeProof?
    suspend fun leaseForPhone(owner: RouterPhoneSession): RouterBoundDialPlan
    suspend fun renewPhoneLease(owner: RouterPhoneSession, stillOwned: () -> Boolean = { true })
    suspend fun phoneStarted(claimId: String, owner: RouterPhoneSession)
    val phoneLeaseOwner: RouterPhoneSession?
    val phoneLeaseDeadline: Long?
    val hasPhoneLease: Boolean
}

internal class RouterHomeAutomationManager(
    val available: Boolean,
    private val scope: CoroutineScope,
    private val phone: RouterHomePhoneController,
    private val linkFactory: suspend () -> RouterHomeLink,
    private val elapsedMs: () -> Long,
    private val userForeground: () -> Boolean,
    private val notificationAccess: () -> Boolean,
    private val localNetworkAccess: () -> Boolean,
    private val startForeground: (Long) -> Unit,
    private val stopForeground: (Long) -> Unit,
    private val persistOff: suspend () -> Unit,
    private val installPreparation: ((suspend (RouterPhoneSession) -> RouterBoundDialPlan?)?) -> Unit,
    private val persistOn: suspend (String) -> Unit = {},
    private val retainedLeases: RouterPhoneLeaseKeeper? = null,
) {
    private val mutableStatus = MutableStateFlow(RouterHomeMonitorState.OFF)
    val status: StateFlow<RouterHomeMonitorState> = mutableStatus
    private val epoch = AtomicLong()
    private val operations = Mutex()
    private val lifecycle = Any()
    @Volatile private var link: RouterHomeLink? = null
    private var loop: Job? = null
    @Volatile private var receipt: RouterPausedPhone? = null
    private var policy = RouterHomePolicy()
    @Volatile private var wanted = false
    @Volatile private var attached = false
    @Volatile private var admitted = false
    @Volatile private var stopping = false
    private var manualAtEnable = 0L
    private var unsupportedProfile: Pair<String, Long>? = null
    val isEnabled: Boolean get() = wanted
    val leaseOwner: RouterPhoneSession? get() = link?.phoneLeaseOwner

    suspend fun routerSelectionChanged() = operations.withLock {
        val ticket = epoch.get()
        val paused = synchronized(lifecycle) {
            wanted = false; admitted = false; attached = false; stopping = true
            installPreparation(null)
            loop?.cancel(); loop = null
            receipt
        }
        paused?.let { owned -> synchronized(lifecycle) {
            phone.resume(owned, null)?.also { receipt = null }
        } }
        retire(ticket)
        retainedLeases?.routerSelectionChanged()
        stopForeground(ticket)
        epoch.compareAndSet(ticket, ticket + 1)
    }

    suspend fun preparePhoneStart(owner: RouterPhoneSession): RouterBoundDialPlan? {
        val ticket = epoch.get()
        val current = link
        if (!wanted || !attached || !admitted || current == null || !current.wifiIsPaired()) return null
        val prepared = try { current.leaseForPhone(owner) }
        catch (_: RouterBootstrapDialUnsupported) {
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                if (!activeRequest(ticket) || link !== current) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
                unsupportedProfile = owner.profileId to owner.profileRevision
                mutableStatus.value = RouterHomeMonitorState.PROFILE_UNSUPPORTED
            }
            return null
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentRequest(ticket) || !wanted || !attached) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
        return prepared
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (!enabled) { disable(explicit = true, expectedEpoch = epoch.get()); return }
        operations.withLock {
            if (wanted) return@withLock
            if (!available || !userForeground() || !notificationAccess()) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
            if (!localNetworkAccess()) throw RouterLocalNetworkPermissionRequired()
            val ticket = epoch.incrementAndGet()
            val manual = phone.manualGeneration.value
            val retainedCandidate = retainedLeases?.borrow()
            val candidate = retainedCandidate ?: linkFactory()
            try {
                candidate.initialize()
                currentCoroutineContext().ensureActive()
                if (ticket != epoch.get() || !userForeground() || !notificationAccess() || !localNetworkAccess()) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
                persistOn(candidate.pairingId)
                currentCoroutineContext().ensureActive()
                if (ticket != epoch.get() || !userForeground() || !notificationAccess() || !localNetworkAccess()) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
                synchronized(lifecycle) {
                    if (retainedCandidate != null && retainedLeases?.adopt(candidate) != true) throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
                    link = candidate
                    admitted = true; wanted = true; attached = false; manualAtEnable = manual
                    stopping = false
                    policy = RouterHomePolicy()
                    unsupportedProfile = null
                    mutableStatus.value = RouterHomeMonitorState.MONITORING
                }
                startForeground(ticket)
            } catch (failure: Exception) {
                if (retainedLeases?.owns(candidate) != true) candidate.close()
                withContext(NonCancellable) {
                    if (ticket == epoch.get()) { retire(ticket); persistOff(); stopForeground(ticket) }
                }
                throw failure
            }
        }
    }

    fun isCurrentRequest(requestEpoch: Long): Boolean = requestEpoch == epoch.get()
    fun isStopping(requestEpoch: Long): Boolean = isCurrentRequest(requestEpoch) && stopping
    fun canForegroundAttach(requestEpoch: Long): Boolean = isCurrentRequest(requestEpoch) && available && wanted && admitted && notificationAccess() && localNetworkAccess()

    private fun activeRequest(ticket: Long): Boolean = isCurrentRequest(ticket) && wanted && attached && admitted && !stopping

    fun foregroundAttached(requestEpoch: Long): Boolean = synchronized(lifecycle) {
        if (!canForegroundAttach(requestEpoch)) return@synchronized false
        if (attached) return@synchronized true
        attached = true
        installPreparation(::preparePhoneStart)
        loop = scope.launch { runLoop(requestEpoch) }.also { job -> job.invokeOnCompletion { failure ->
            if (failure != null && isCurrentRequest(requestEpoch) && wanted) {
                retire(requestEpoch)
                persistOffLater(requestEpoch)
                stopForeground(requestEpoch)
            }
        } }
        true
    }

    fun foregroundStopped(requestEpoch: Long) {
        val retired = retire(requestEpoch, unlessStopping = true)
        if (!retired) return
        persistOffLater(requestEpoch)
    }

    private fun persistOffLater(ticket: Long) {
        scope.launch { operations.withLock { if (isCurrentRequest(ticket) && !wanted) persistOff() } }
    }

    fun disableFromService(requestEpoch: Long) {
        if (!isCurrentRequest(requestEpoch)) return
        scope.launch { disable(explicit = true, expectedEpoch = requestEpoch) }
    }

    private suspend fun disable(explicit: Boolean, expectedEpoch: Long) = operations.withLock {
        if (!isCurrentRequest(expectedEpoch)) return@withLock
        val ticket = epoch.get()
        val (oldLink, oldReceipt) = synchronized(lifecycle) {
            stopping = true
            wanted = false; admitted = false; attached = false
            installPreparation(null)
            loop?.cancel(); loop = null
            link to receipt
        }
        try {
            if (explicit && oldReceipt != null) {
                withTimeoutOrNull(4000) {
                    try {
                        val plan = if (oldLink?.wifiIsPaired() == true) oldLink.leaseForPhone(oldReceipt.ownership.owner) else null
                        currentCoroutineContext().ensureActive()
                        val started = synchronized(lifecycle) {
                            if (isCurrentRequest(ticket) && stopping && !wanted) phone.resume(oldReceipt, plan)?.also { receipt = null } else null
                        }
                        if (started != null && plan != null) oldLink?.phoneStarted(plan.claimId, started)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
            }
            if (explicit && oldLink != null && retainedLeases != null) {
                val owner = oldLink.phoneLeaseOwner
                synchronized(lifecycle) {
                    if (owner != null && isCurrentRequest(ticket) && stopping && link === oldLink &&
                        retainedLeases.retain(oldLink, owner, phone.manualGeneration.value)) link = null
                }
            }
        } finally {
            withContext(NonCancellable) { retire(ticket); persistOff(); stopForeground(ticket) }
        }
    }

    private fun retire(ticket: Long, unlessStopping: Boolean = false): Boolean {
        val old = synchronized(lifecycle) {
            if (!isCurrentRequest(ticket) || unlessStopping && stopping) return false
            wanted = false; admitted = false; attached = false
            stopping = false
            installPreparation(null)
            loop?.cancel(); loop = null
            (link to receipt).also {
                link = null; receipt = null
                policy = RouterHomePolicy()
                unsupportedProfile = null
                mutableStatus.value = RouterHomeMonitorState.OFF
            }
        }
        old.first?.close()
        old.second?.let(phone::forget)
        return true
    }

    private suspend fun runLoop(ticket: Long) = coroutineScope {
        val events = Channel<Unit>(Channel.CONFLATED)
        val phoneChanges = launch { phone.changes.collect { events.trySend(Unit) } }
        val manualChanges = launch { phone.manualGeneration.collect { events.trySend(Unit) } }
        val connection = requireNotNull(link)
        val networks = launch { connection.changes.collect { events.trySend(Unit) } }
        var manual = manualAtEnable
        try {
            while (isCurrentRequest(ticket) && wanted && attached) {
                if (!notificationAccess() || !localNetworkAccess()) {
                    retire(ticket)
                    persistOffLater(ticket)
                    stopForeground(ticket)
                    break
                }
                synchronized(lifecycle) {
                    if (phone.manualGeneration.value != manual) {
                        manual = phone.manualGeneration.value
                        policy.manualPhoneAction()
                        receipt?.let(phone::forget); receipt = null
                    }
                }
                var waitMs = 5000L
                var attemptedProfile: Pair<String, Long>? = null
                try {
                    var wifi = connection.wifiIsPaired()
                    val observed = if (wifi) try { connection.status() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: RouterHomeNotPairedNetwork) { wifi = false; null }
                        catch (changed: RouterHomePairingChanged) { throw changed }
                        catch (failure: RouterApiFailure) {
                            if (failure.kind in setOf(RouterApiError.PIN, RouterApiError.CHANGE_PIN)) throw RouterHomePairingChanged()
                            if (failure.kind == RouterApiError.IDENTITY) { wifi = false; null } else null
                        }
                        catch (_: Exception) { null } else null
                    waitMs = if (wifi) 5000L else 30000L
                    currentCoroutineContext().ensureActive()
                    if (!activeRequest(ticket)) break
                    val operationContext = currentCoroutineContext()
                    val state = phone.snapshot()
                    attemptedProfile = state.selectedProfileId?.let { it to state.profileRevision }
                    synchronized(lifecycle) {
                        if (unsupportedProfile != attemptedProfile) unsupportedProfile = null
                    }
                    if (wifi) state.connected?.let { owner -> connection.renewPhoneLease(owner) {
                        synchronized(lifecycle) { operationContext.isActive && activeRequest(ticket) &&
                            phone.manualGeneration.value == manual && phone.snapshot().connected == owner }
                    } }
                    if (state.idle && connection.hasPhoneLease && observed?.proof != null) {
                        val leaseOwner = connection.phoneLeaseOwner
                        connection.routedAfterLeaseRelease(observed.proof, leaseOwner) {
                            synchronized(lifecycle) { operationContext.isActive && activeRequest(ticket) &&
                                phone.manualGeneration.value == manual && phone.snapshot().idle }
                        }
                    }
                    val action = synchronized(lifecycle) {
                        if (!operationContext.isActive || !activeRequest(ticket) || phone.manualGeneration.value != manual) RouterHomeAction.None
                        else policy.evaluate(true, connection.pairingId, wifi, observed?.proof, elapsedMs(),
                            state.connected, state.selectedProfileId, state.profileRevision, state.idle, observed?.confirmedOff)
                    }
                    when (action) {
                        is RouterHomeAction.Pause -> {
                            try {
                                connection.validatePhoneResume(action.ownership.owner)
                                currentCoroutineContext().ensureActive()
                                synchronized(lifecycle) {
                                    if (activeRequest(ticket) && unsupportedProfile == attemptedProfile) unsupportedProfile = null
                                }
                                val proof = observed?.proof?.let { connection.routedAfterLeaseRelease(it, action.ownership.owner) {
                                    synchronized(lifecycle) { operationContext.isActive && activeRequest(ticket) &&
                                        phone.manualGeneration.value == manual && phone.snapshot().connected == action.ownership.owner }
                                } }
                                currentCoroutineContext().ensureActive()
                                synchronized(lifecycle) {
                                    if (operationContext.isActive && activeRequest(ticket) && phone.manualGeneration.value == manual &&
                                        proof != null && elapsedMs() >= proof.verifiedAtElapsedMs && elapsedMs() - proof.verifiedAtElapsedMs <= 5000) {
                                        receipt = phone.pause(action.ownership)
                                        if (receipt == null) policy.pauseFailed(action.ownership)
                                    } else policy.pauseFailed(action.ownership)
                                }
                            } catch (failure: Exception) { synchronized(lifecycle) { policy.pauseFailed(action.ownership) }; throw failure }
                        }
                        is RouterHomeAction.Resume -> {
                            val paused = receipt?.takeIf { it.ownership == action.ownership }
                            if (paused != null) {
                                try {
                                    val plan = if (wifi) connection.leaseForPhone(paused.ownership.owner) else null
                                    currentCoroutineContext().ensureActive()
                                    val started = synchronized(lifecycle) {
                                        if (operationContext.isActive && activeRequest(ticket) && phone.manualGeneration.value == manual)
                                            phone.resume(paused, plan)?.also { receipt = null }
                                        else null
                                    }
                                    if (started != null && plan != null) connection.phoneStarted(plan.claimId, started)
                                    if (started == null) synchronized(lifecycle) { policy.resumeFailed(action.ownership, elapsedMs()) }
                                } catch (failure: Exception) {
                                    synchronized(lifecycle) { policy.resumeFailed(action.ownership, elapsedMs()) }
                                    throw failure
                                }
                            }
                        }
                        RouterHomeAction.None -> Unit
                    }
                    synchronized(lifecycle) {
                        if (operationContext.isActive && activeRequest(ticket)) mutableStatus.value =
                            if (unsupportedProfile != null) RouterHomeMonitorState.PROFILE_UNSUPPORTED
                            else if (wifi && observed == null) RouterHomeMonitorState.ROUTER_UNAVAILABLE else RouterHomeMonitorState.MONITORING
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RouterHomePairingChanged) { retire(ticket); persistOffLater(ticket); stopForeground(ticket); break }
                catch (_: RouterBootstrapDialUnsupported) { synchronized(lifecycle) {
                    val current = phone.snapshot()
                    if (activeRequest(ticket) && attemptedProfile != null && attemptedProfile == current.selectedProfileId?.let { it to current.profileRevision }) {
                        unsupportedProfile = attemptedProfile
                        mutableStatus.value = RouterHomeMonitorState.PROFILE_UNSUPPORTED
                    }
                } }
                catch (_: Exception) { synchronized(lifecycle) {
                    if (activeRequest(ticket)) mutableStatus.value = RouterHomeMonitorState.ROUTER_UNAVAILABLE
                } }
                withTimeoutOrNull(waitMs) { events.receive() }
            }
        } finally { phoneChanges.cancel(); manualChanges.cancel(); networks.cancel(); events.close() }
    }
}
