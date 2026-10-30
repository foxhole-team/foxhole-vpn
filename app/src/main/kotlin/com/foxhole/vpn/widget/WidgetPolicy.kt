package com.foxhole.vpn.widget

import com.foxhole.vpn.R
import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState

internal enum class WidgetAction { START, STOP }

internal enum class WidgetDecision { NONE, REQUEST_PERMISSION, CONNECT, DISCONNECT }

internal data class WidgetLocation(val countryCode: String, val name: String, val cached: Boolean)

internal fun widgetLocation(state: VpnUiState): WidgetLocation? {
    if (!state.isReady) return null
    if (state.isConnected) return state.location?.let {
        WidgetLocation(it.countryCode, it.city.ifBlank { it.country }, cached = false)
    }
    if (state.canDisconnect) return null
    val device = state.deviceLocation
    if (device.status != DeviceLocationStatus.CURRENT && device.status != DeviceLocationStatus.CACHED) return null
    return device.location?.let {
        WidgetLocation(it.countryCode, it.city.ifBlank { it.country }, cached = device.status == DeviceLocationStatus.CACHED)
    }
}

internal fun widgetLocationLabel(location: WidgetLocation?): String? = location?.name?.takeIf(String::isNotBlank)

internal fun widgetLocationPlaceholder(state: VpnUiState): Int = if (
    state.phase == VpnPhase.CONNECTING || state.phase == VpnPhase.VALIDATING ||
    state.isReady && state.phase == VpnPhase.IDLE && !state.canDisconnect && state.deviceLocation.status == DeviceLocationStatus.LOADING
) R.string.widget_location_determining else R.string.widget_detail_empty

internal fun widgetAction(state: VpnUiState): WidgetAction? = when {
    state.hasOwnedTunnel -> WidgetAction.STOP
    state.phase == VpnPhase.STOPPING -> null
    state.canDisconnect -> WidgetAction.STOP
    !state.isReady -> null
    state.phase == VpnPhase.IDLE || state.phase == VpnPhase.ERROR ->
        WidgetAction.START.takeIf { state.selectedProfile != null }
    else -> null
}

internal fun widgetDecision(
    action: WidgetAction,
    requestedProfileId: String?,
    state: VpnUiState,
    permissionGranted: Boolean,
): WidgetDecision = when (action) {
    WidgetAction.STOP ->
        if (widgetAction(state) == WidgetAction.STOP) WidgetDecision.DISCONNECT else WidgetDecision.NONE
    WidgetAction.START -> when {
        !state.isReady -> WidgetDecision.NONE
        state.canDisconnect -> WidgetDecision.NONE
        state.phase != VpnPhase.IDLE && state.phase != VpnPhase.ERROR -> WidgetDecision.NONE
        requestedProfileId == null || state.selectedProfile?.id != requestedProfileId -> WidgetDecision.NONE
        !permissionGranted -> WidgetDecision.REQUEST_PERMISSION
        else -> WidgetDecision.CONNECT
    }
}

internal fun widgetShowsRuntimeStatus(state: VpnUiState): Boolean =
    state.isReady || state.canDisconnect || state.phase == VpnPhase.ERROR

internal fun widgetStatusLabel(state: VpnUiState): Int = if (!widgetShowsRuntimeStatus(state)) {
    R.string.widget_loading
} else when (state.phase) {
    VpnPhase.IDLE -> R.string.widget_disconnected
    VpnPhase.CONNECTING -> R.string.widget_connecting
    VpnPhase.VALIDATING -> R.string.widget_validating
    VpnPhase.CONNECTED -> R.string.widget_connected
    VpnPhase.STOPPING -> R.string.widget_stopping
    VpnPhase.ERROR -> R.string.widget_error
}

internal fun widgetFoxFrameIndex(phase: VpnPhase): Int = if (phase == VpnPhase.CONNECTED) 0 else 3

internal fun widgetUsesDarkTheme(theme: VpnTheme, systemDark: Boolean): Boolean = when (theme) {
    VpnTheme.SYSTEM -> systemDark
    VpnTheme.DARK, VpnTheme.NIGHT -> true
    VpnTheme.LIGHT -> false
}
