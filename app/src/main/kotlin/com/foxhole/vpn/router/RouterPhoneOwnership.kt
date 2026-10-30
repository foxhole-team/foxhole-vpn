package com.foxhole.vpn.router

import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress

internal data class RouterPausedPhone(val ownership: RouterAutomaticPause, val stopGeneration: Long, val manualGeneration: Long)
internal data class RouterHomePhoneSnapshot(val selectedProfileId: String?, val profileRevision: Long,
    val connected: RouterPhoneSession?, val idle: Boolean, val pending: RouterPhoneSession? = null)

internal interface RouterHomePhoneController {
    val changes: StateFlow<VpnUiState>
    val manualGeneration: StateFlow<Long>
    fun snapshot(): RouterHomePhoneSnapshot
    fun pause(owner: RouterAutomaticPause): RouterPausedPhone?
    suspend fun dialCandidate(owner: RouterPhoneSession, resolver: suspend (String) -> List<InetAddress>): RouterDialCandidate
    fun resume(receipt: RouterPausedPhone, plan: RouterBoundDialPlan? = null): RouterPhoneSession?
    fun forget(receipt: RouterPausedPhone)
}

internal fun routerConnectedPhone(ui: VpnUiState, runtime: EngineSnapshot, commandGeneration: Long,
    pendingStart: Boolean, storageHealthy: Boolean): RouterPhoneSession? {
    val id = ui.selectedProfileId ?: return null
    val ticket = ui.connectionId ?: return null
    if (!storageHealthy || !ui.isReady || !ui.isConnected || !ui.hasOwnedTunnel || pendingStart ||
        !runtime.hasOwnedTunnel || runtime.phase != EnginePhase.CONNECTED || runtime.profileId != id ||
        runtime.commandTicket != ticket || commandGeneration != ticket) return null
    return RouterPhoneSession(id, ui.profileRevision, ticket)
}

internal fun routerResumeOwned(receipt: RouterPausedPhone, actualReceipt: RouterPausedPhone?, ui: VpnUiState,
    runtime: EngineSnapshot, commandGeneration: Long, manualGeneration: Long, pendingStart: Boolean,
    storageHealthy: Boolean, anotherVpn: Boolean): Boolean = actualReceipt == receipt && storageHealthy && ui.isReady &&
    ui.phase == VpnPhase.IDLE && !ui.canDisconnect && !ui.hasOwnedTunnel && !ui.hasPendingStart && !pendingStart &&
    runtime.phase == EnginePhase.IDLE && !runtime.hasOwnedTunnel && commandGeneration == receipt.stopGeneration &&
    manualGeneration == receipt.manualGeneration && ui.selectedProfileId == receipt.ownership.owner.profileId &&
    ui.profileRevision == receipt.ownership.owner.profileRevision && !anotherVpn

internal fun routerAutomaticProfilesProtected(receipt: RouterPausedPhone?, commandGeneration: Long, manualGeneration: Long,
    selectedProfileId: String?, profileRevision: Long): Boolean = receipt != null && receipt.stopGeneration == commandGeneration &&
    receipt.manualGeneration == manualGeneration && receipt.ownership.owner.profileId == selectedProfileId && receipt.ownership.owner.profileRevision == profileRevision

internal fun routerAutomaticLeaseProtected(owner: RouterPhoneSession?, commandGeneration: Long, selectedProfileId: String?,
    profileRevision: Long, ownedOrPendingTunnel: Boolean): Boolean = owner != null && ownedOrPendingTunnel &&
    owner.connectionId == commandGeneration && owner.profileId == selectedProfileId && owner.profileRevision == profileRevision

internal fun routerAutomaticPreparedLeaseProtected(start: Pair<Long, RouterBoundDialPlan>?, commandGeneration: Long,
    selectedProfileId: String?, profileRevision: Long, ownedOrPendingTunnel: Boolean, now: Long): Boolean = start != null &&
    now >= 0 && now < start.second.expiresAtElapsedMs && routerAutomaticLeaseProtected(
        RouterPhoneSession(start.second.owner.profileId, start.second.owner.profileRevision, start.first), commandGeneration,
        selectedProfileId, profileRevision, ownedOrPendingTunnel)
