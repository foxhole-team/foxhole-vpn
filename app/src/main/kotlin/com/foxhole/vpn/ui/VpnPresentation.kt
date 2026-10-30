package com.foxhole.vpn.ui

import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState

internal enum class VpnPrimaryAction { CONNECT, DISCONNECT }

internal fun primaryAction(state: VpnUiState): VpnPrimaryAction = when {
    state.phase == VpnPhase.STOPPING && state.canRestartDuringStop && !state.hasPendingStart && state.message == null -> VpnPrimaryAction.CONNECT
    state.canDisconnect || state.isConnected -> VpnPrimaryAction.DISCONNECT
    else -> VpnPrimaryAction.CONNECT
}

internal fun primaryActionEnabled(state: VpnUiState): Boolean =
    primaryAction(state) == VpnPrimaryAction.DISCONNECT || (state.isReady && state.selectedProfile != null)

internal fun statusLabel(phase: VpnPhase): Int = when (phase) {
    VpnPhase.IDLE -> R.string.vpn_status_idle
    VpnPhase.CONNECTING -> R.string.vpn_status_connecting
    VpnPhase.VALIDATING -> R.string.vpn_status_validating
    VpnPhase.CONNECTED -> R.string.vpn_status_connected
    VpnPhase.STOPPING -> R.string.vpn_status_stopping
    VpnPhase.ERROR -> R.string.vpn_status_error
}

internal fun primaryLabel(state: VpnUiState): Int = when (primaryAction(state)) {
    VpnPrimaryAction.CONNECT -> R.string.vpn_start
    VpnPrimaryAction.DISCONNECT -> R.string.vpn_stop
}

internal fun validatedExitLocation(state: VpnUiState) = state.location.takeIf { state.isConnected }

internal fun countryAssetCode(countryCode: String): String? =
    countryCode.lowercase(java.util.Locale.ROOT).takeIf { code -> code.length == 2 && code.all { it in 'a'..'z' } }
