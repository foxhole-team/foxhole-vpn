package com.foxhole.vpn.runtime

import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState

internal enum class VpnSystemAction { START, STOP, NONE }
internal enum class VpnTileClick { OPEN_APP, START_ACTIVITY, STOP, NONE }

internal data class VpnSystemPresentation(val statusRes: Int, val action: VpnSystemAction, val active: Boolean) {
    val accent: Int get() = if (statusRes == R.string.vpn_notification_connected) 0xFF1E6C39.toInt() else 0xFFB3261E.toInt()
}

internal fun vpnSystemPresentation(state: VpnUiState): VpnSystemPresentation {
    val active = state.canDisconnect || state.isConnected
    val status = when (state.phase) {
        VpnPhase.IDLE -> if (!state.isReady && !active) R.string.widget_loading else R.string.vpn_notification_disconnected
        VpnPhase.CONNECTING -> R.string.vpn_status_connecting
        VpnPhase.VALIDATING -> R.string.vpn_status_validating
        VpnPhase.CONNECTED -> R.string.vpn_notification_connected
        VpnPhase.STOPPING -> R.string.vpn_status_stopping
        VpnPhase.ERROR -> R.string.vpn_status_error
    }
    val action = when {
        state.phase == VpnPhase.STOPPING -> VpnSystemAction.NONE
        active -> VpnSystemAction.STOP
        state.isReady -> VpnSystemAction.START
        else -> VpnSystemAction.NONE
    }
    return VpnSystemPresentation(status, action, active)
}

internal fun vpnTileClick(state: VpnUiState, licenseLoaded: Boolean, licenseAccepted: Boolean): VpnTileClick =
    when (vpnSystemPresentation(state).action) {
        VpnSystemAction.STOP -> VpnTileClick.STOP
        VpnSystemAction.NONE -> VpnTileClick.NONE
        VpnSystemAction.START -> if (licenseLoaded && licenseAccepted && state.selectedProfile != null)
            VpnTileClick.START_ACTIVITY else VpnTileClick.OPEN_APP
    }

// A rebind can follow a cancelled unlock. Old callbacks never inherit the new user interaction.
internal class VpnTileUnlockGate {
    private var generation = 0L
    var pending: Boolean = false
        private set

    fun newListeningSession() { generation++; pending = false }
    fun claim(): Long? {
        if (pending) return null
        pending = true
        return ++generation
    }
    fun consume(ticket: Long): Boolean {
        if (!pending || ticket != generation) return false
        generation++; pending = false
        return true
    }
}
