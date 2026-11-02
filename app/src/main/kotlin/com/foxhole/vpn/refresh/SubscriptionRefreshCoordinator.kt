package com.foxhole.vpn.refresh

import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.profile.ImportedVpnProfiles
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class SubscriptionRefreshCoordinator(
    private val scope: CoroutineScope,
    private val foregroundLock: Any,
    private val awaitReady: suspend () -> Unit,
    private val storageAvailable: () -> Boolean,
    private val settings: () -> VpnSettings,
    private val attempts: () -> Map<String, RefreshAttempt>,
    private val targets: () -> List<SubscriptionRefreshTarget>,
    private val currentGeneration: () -> Long,
    private val profilesProtected: () -> Boolean,
    private val load: suspend (SubscriptionRefreshTarget) -> ImportedVpnProfiles,
    private val commit: suspend (SubscriptionRefreshTarget, ImportedVpnProfiles, Long) -> RefreshOutcome,
    private val recordAttempt: suspend (SubscriptionRefreshTarget, Long, RefreshOutcome) -> Boolean,
) {
    private var foregroundJob: Job? = null
    private val operations = Mutex()
    private val mutable = MutableStateFlow(SubscriptionRefreshRun())
    val state: StateFlow<SubscriptionRefreshRun> = mutable

    fun foregroundEntered() = synchronized(foregroundLock) {
        if (foregroundJob?.isActive != true) foregroundJob = scope.launch { refreshAutomatically() }
    }

    suspend fun refreshAutomatically(): SubscriptionRefreshRun {
        awaitReady()
        if (!storageAvailable() || !settings().autoRefreshSubscriptions) return SubscriptionRefreshRun()
        return operations.withLock {
            if (!storageAvailable() || !settings().autoRefreshSubscriptions) return@withLock SubscriptionRefreshRun()
            val now = System.currentTimeMillis()
            val interval = refreshIntervalMillis(settings().subscriptionRefreshIntervalHours, 12)
            val dueTargets = targets()
                .filter { refreshIsDue(attempts()[it.key], now, interval) }
                .sortedBy { attempts()[it.key]?.lastAttemptAtMillis ?: 0 }
            val generation = currentGeneration()
            var report = SubscriptionRefreshRun()
            try {
                val completed = withTimeoutOrNull(8 * 60 * 1000L) {
                    for (target in dueTargets) {
                        currentCoroutineContext().ensureActive()
                        if (!settings().autoRefreshSubscriptions || currentGeneration() != generation) {
                            report = report.copy(stale = report.stale + dueTargets.size - report.checked)
                            break
                        }
                        if (profilesProtected()) {
                            recordAttempt(target, generation, RefreshOutcome.DEFERRED)
                            report = report.copy(checked = report.checked + 1, deferred = report.deferred + 1)
                            continue
                        }
                        if (!recordAttempt(target, generation, RefreshOutcome.CHECKING)) continue
                        val outcome = try {
                            commit(target, load(target), generation)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            report = report.copy(retry = report.retry || failure is IOException)
                            RefreshOutcome.FAILED
                        }
                        if (outcome in setOf(RefreshOutcome.DEFERRED, RefreshOutcome.STALE, RefreshOutcome.FAILED)) {
                            recordAttempt(target, generation, outcome)
                        }
                        report = report.copy(
                            checked = report.checked + 1,
                            updated = report.updated + if (outcome == RefreshOutcome.UPDATED) 1 else 0,
                            failed = report.failed + if (outcome == RefreshOutcome.FAILED) 1 else 0,
                            deferred = report.deferred + if (outcome == RefreshOutcome.DEFERRED) 1 else 0,
                            stale = report.stale + if (outcome == RefreshOutcome.STALE) 1 else 0,
                        )
                    }
                    true
                }
                if (completed == null) report = report.copy(retry = true)
                report
            } finally { mutable.value = report }
        }
    }

    // Settings publication must retain its existing command/activity lock order.
    fun cancelAutomaticRefresh() { foregroundJob?.cancel() }

    fun schedulingFailed() { mutable.value = SubscriptionRefreshRun(failed = 1, retry = true) }
}
