package com.foxhole.vpn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ProfileMutationOwner(
    val generation: Long,
    val phase: VpnPhase,
    val hasOwnedTunnel: Boolean,
    val hasPendingStart: Boolean,
)

internal enum class ProfileMutationResult { APPLIED, SUPERSEDED }

internal enum class ProfileMutationAction { KEEP, STOP, RECONNECT }

internal class ProfileMutationSupersededException : IllegalStateException("profile_mutation_superseded")

internal class ProfileMutationStorageFaultException : IllegalStateException("profile_mutation_store_fault")

internal fun profileMutationAdmitted(
    expected: ProfileMutationOwner,
    current: ProfileMutationOwner,
    changesSelection: Boolean,
    preservingCommand: Long?,
): Boolean = when {
    !changesSelection -> true
    expected.generation != current.generation -> false
    preservingCommand != null -> expected.generation == preservingCommand && expected.hasPendingStart && current.hasPendingStart
    expected.hasPendingStart || current.hasPendingStart -> false
    else -> when (expected.phase) {
        VpnPhase.IDLE, VpnPhase.ERROR ->
            !expected.hasOwnedTunnel && !current.hasOwnedTunnel && current.phase in setOf(VpnPhase.IDLE, VpnPhase.ERROR)
        VpnPhase.CONNECTED -> expected.hasOwnedTunnel && current.hasOwnedTunnel && current.phase == VpnPhase.CONNECTED
        else -> false
    }
}

internal fun profileMutationAction(
    expected: ProfileMutationOwner,
    selectedIdChanged: Boolean,
    selectedConfigChanged: Boolean,
    preservingCommand: Long?,
): ProfileMutationAction = when {
    !selectedIdChanged && !selectedConfigChanged -> ProfileMutationAction.KEEP
    preservingCommand != null -> ProfileMutationAction.STOP
    expected.phase == VpnPhase.CONNECTED && selectedIdChanged -> ProfileMutationAction.STOP
    expected.phase == VpnPhase.CONNECTED && selectedConfigChanged -> ProfileMutationAction.RECONNECT
    else -> ProfileMutationAction.KEEP
}

internal suspend fun <T> commitProfileMutation(
    before: T,
    candidate: T,
    write: suspend ((T) -> T) -> T,
    admitted: () -> Boolean,
    onRollbackFailure: () -> Unit = {},
    publishIfCurrent: (T) -> Boolean,
): ProfileMutationResult {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (!admitted()) return ProfileMutationResult.SUPERSEDED
    if (candidate == before) return ProfileMutationResult.APPLIED
    return withContext(NonCancellable) {
        var accepted = false
        val next = write { current ->
            caller.ensureActive()
            check(current == before) { "Profile store changed outside its mutation owner" }
            if (!admitted()) current else candidate.also { accepted = true }
        }
        when {
            !accepted -> ProfileMutationResult.SUPERSEDED
            publishIfCurrent(next) -> ProfileMutationResult.APPLIED
            else -> {
                try {
                    write { current ->
                        check(current == next) { "Profile store changed before mutation rollback" }
                        before
                    }
                } catch (failure: Throwable) {
                    onRollbackFailure()
                    throw failure
                }
                ProfileMutationResult.SUPERSEDED
            }
        }
    }
}
