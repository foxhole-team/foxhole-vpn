package com.foxhole.vpn

import com.foxhole.vpn.engine.EngineFailure
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot

internal enum class ProtocolChoiceAction { NONE, IMMEDIATE, CONFIRM }

internal fun protocolChoiceAction(state: VpnUiState, profileId: String, optionId: String): ProtocolChoiceAction {
    val profile = state.profiles.firstOrNull { it.id == profileId } ?: return ProtocolChoiceAction.NONE
    if (!state.isReady || profile.selectedProtocolOptionId == optionId || profile.protocolOptions.none { it.id == optionId }) {
        return ProtocolChoiceAction.NONE
    }
    if (state.hasOwnedTunnel) {
        return if (state.selectedProfileId == profileId && state.connectionId != null &&
            state.phase in setOf(VpnPhase.CONNECTED, VpnPhase.VALIDATING, VpnPhase.ERROR)
        ) ProtocolChoiceAction.CONFIRM else ProtocolChoiceAction.NONE
    }
    return if (!state.canDisconnect && !state.isConnected) ProtocolChoiceAction.IMMEDIATE else ProtocolChoiceAction.NONE
}

internal fun protocolChangeRequest(state: VpnUiState, profileId: String, optionId: String): VpnProtocolChangeRequest? {
    if (protocolChoiceAction(state, profileId, optionId) != ProtocolChoiceAction.CONFIRM) return null
    return VpnProtocolChangeRequest(profileId, optionId, state.selectedProfile?.selectedProtocolOptionId,
        requireNotNull(state.connectionId), state.profileRevision)
}

internal fun protocolChangeCurrent(request: VpnProtocolChangeRequest, state: VpnUiState): Boolean =
    state.connectionId == request.connectionId && state.profileRevision == request.profileRevision &&
        state.selectedProfile?.selectedProtocolOptionId == request.previousOptionId &&
        protocolChoiceAction(state, request.profileId, request.optionId) == ProtocolChoiceAction.CONFIRM

internal fun protocolChangeOwnerCurrent(
    request: VpnProtocolChangeRequest,
    state: VpnUiState,
    snapshot: EngineSnapshot,
    generation: Long,
    pendingStart: Boolean,
): Boolean = protocolChangeCurrent(request, state) && !pendingStart && generation == request.connectionId &&
    snapshot.commandTicket == request.connectionId && snapshot.profileId == request.profileId && snapshot.hasOwnedTunnel &&
    snapshot.phase in setOf(EnginePhase.CONNECTED, EnginePhase.VALIDATING, EnginePhase.ERROR) && snapshot.failure != EngineFailure.STOP

internal fun profileRemovalCurrent(state: VpnUiState, profileId: String, revision: Long): Boolean =
    state.isReady && !state.canDisconnect && !state.isConnected && state.profileRevision == revision &&
        state.profiles.any { it.id == profileId }
