package com.foxhole.vpn.refresh

import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.updates.VpnDataUpdateError
import com.foxhole.vpn.updates.VpnDataUpdatePhase
import com.foxhole.vpn.updates.VpnDataUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ConnectionDataRefreshCoordinator(
    private val scope: CoroutineScope,
    private val foregroundLock: Any,
    private val awaitReady: suspend () -> Unit,
    private val storageAvailable: () -> Boolean,
    private val settings: () -> VpnSettings,
    private val attempt: () -> RefreshAttempt,
    private val updater: VpnDataUpdater,
    private val recordAttempt: suspend (RefreshOutcome) -> Unit,
) {
    private var checkJob: Job? = null
    private var updateJob: Job? = null
    private var checkEpoch = 0
    private val operations = Mutex()

    fun foregroundChanged(visible: Boolean) = synchronized(foregroundLock) {
        if (!visible) {
            checkEpoch++
            checkJob?.cancel()
            checkJob = null
            return@synchronized
        }
        if (checkJob?.isActive == true || updateJob?.isActive == true) return@synchronized
        val epoch = ++checkEpoch
        checkJob = scope.launch {
            try {
                awaitReady()
                checkAutomatically()
            } finally {
                synchronized(foregroundLock) { if (epoch == checkEpoch) checkJob = null }
            }
        }
    }

    fun update() = synchronized(foregroundLock) {
        if (updateJob?.isActive == true) return@synchronized
        checkEpoch++
        checkJob?.cancel()
        checkJob = null
        updateJob = scope.launch {
            awaitReady()
            operations.withLock {
                updater.update()
                if (updater.state.value.phase in setOf(VpnDataUpdatePhase.UPDATED, VpnDataUpdatePhase.UP_TO_DATE)) {
                    recordAttempt(RefreshOutcome.UNCHANGED)
                }
            }
        }
    }

    suspend fun checkAutomatically(): Boolean {
        awaitReady()
        if (!storageAvailable()) return false
        return operations.withLock {
            if (!settings().autoCheckConnectionData) return@withLock false
            val stamp = attempt()
            val dueStamp = if (updater.hasVerifiedCheckCache) stamp else stamp.copy(lastSuccessAtMillis = 0)
            val interval = refreshIntervalMillis(settings().connectionDataCheckIntervalHours, 24)
            if (!refreshIsDue(dueStamp, System.currentTimeMillis(), interval)) return@withLock false
            recordAttempt(RefreshOutcome.CHECKING)
            updater.check()
            val current = updater.state.value
            val outcome = when (current.phase) {
                VpnDataUpdatePhase.AVAILABLE -> RefreshOutcome.AVAILABLE
                VpnDataUpdatePhase.UP_TO_DATE -> RefreshOutcome.UNCHANGED
                else -> RefreshOutcome.FAILED
            }
            recordAttempt(outcome)
            current.error in setOf(VpnDataUpdateError.NETWORK, VpnDataUpdateError.STORAGE)
        }
    }

    // Publication already holds the command fence; do not acquire the activity monitor here.
    fun cancelAutomaticCheck() { checkJob?.cancel() }
}
