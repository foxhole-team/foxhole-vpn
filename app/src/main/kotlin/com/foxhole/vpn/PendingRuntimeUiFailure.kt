package com.foxhole.vpn

import com.foxhole.vpn.engine.EngineFailure
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot

internal data class PendingRuntimeUiFailure(val generation: Long, val resource: Int)

internal fun projectVpnRuntime(
    current: VpnUiState,
    snapshot: EngineSnapshot,
    generation: Long,
    pendingStart: Boolean,
    pendingFailure: PendingRuntimeUiFailure?,
    profileStoreFault: String?,
    runtimeMessage: (EngineFailure) -> String,
    resourceMessage: (Int) -> String,
): VpnUiState {
    val terminal = pendingFailure?.takeIf { it.generation == generation }?.let { resourceMessage(it.resource) }
    val matchingOwner = snapshot.commandTicket != null && snapshot.commandTicket == generation
    val ownedError = snapshot.phase == EnginePhase.ERROR && snapshot.hasOwnedTunnel
    val phase = when {
        ownedError -> VpnPhase.ERROR
        profileStoreFault != null -> if (pendingStart || snapshot.phase in setOf(EnginePhase.CONNECTING, EnginePhase.VALIDATING, EnginePhase.STOPPING)) {
            VpnPhase.STOPPING
        } else VpnPhase.ERROR
        terminal != null -> if (snapshot.hasOwnedTunnel || snapshot.phase in setOf(EnginePhase.CONNECTING, EnginePhase.VALIDATING, EnginePhase.CONNECTED, EnginePhase.STOPPING)) {
            VpnPhase.STOPPING
        } else VpnPhase.ERROR
        pendingStart -> if (snapshot.hasOwnedTunnel) VpnPhase.STOPPING else VpnPhase.CONNECTING
        !matchingOwner && snapshot.phase in setOf(EnginePhase.CONNECTING, EnginePhase.VALIDATING, EnginePhase.CONNECTED) -> VpnPhase.STOPPING
        else -> VpnPhase.valueOf(snapshot.phase.name)
    }
    val message = when {
        snapshot.failure == EngineFailure.STOP -> runtimeMessage(EngineFailure.STOP)
        profileStoreFault != null -> profileStoreFault
        terminal != null -> terminal
        matchingOwner || ownedError -> snapshot.failure?.let(runtimeMessage)
        else -> null
    }
    return current.copy(
        isReady = current.isReady && profileStoreFault == null,
        phase = phase,
        hasOwnedTunnel = snapshot.hasOwnedTunnel,
        hasPendingStart = pendingStart,
        canRestartDuringStop = snapshot.phase == EnginePhase.STOPPING && !pendingStart && terminal == null && profileStoreFault == null,
        connectionId = snapshot.commandTicket.takeIf { snapshot.hasOwnedTunnel },
        location = snapshot.location?.takeIf { matchingOwner && phase == VpnPhase.CONNECTED && !pendingStart }?.asVpnLocation(),
        message = message,
    )
}
