package com.foxhole.vpn.widget

import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun widgetHomeRefreshAllowed(state: VpnUiState, hasWidgets: Boolean, hasPendingStart: Boolean): Boolean =
    hasWidgets && state.isReady && !hasPendingStart && !state.canDisconnect &&
        state.phase in setOf(VpnPhase.IDLE, VpnPhase.ERROR)

internal suspend fun <N : Any> refreshWidgetHomeLocation(
    admitted: () -> Boolean,
    currentNetwork: () -> N?,
    lookup: suspend (N) -> VpnLocation,
    commit: suspend (N, VpnLocation) -> Boolean,
): Boolean {
    currentCoroutineContext().ensureActive()
    if (!admitted()) return false
    val network = currentNetwork() ?: return false
    val location = lookup(network)
    currentCoroutineContext().ensureActive()
    if (!admitted() || currentNetwork() != network) return false
    return commit(network, location)
}
