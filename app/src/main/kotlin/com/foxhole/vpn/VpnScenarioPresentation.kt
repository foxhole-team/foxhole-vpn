package com.foxhole.vpn

import androidx.annotation.StringRes

internal enum class VpnScenarioAppFilter { ALL, SYSTEM, USER }

internal fun vpnScenarioAppVisible(system: Boolean?, filter: VpnScenarioAppFilter): Boolean = when (filter) {
    VpnScenarioAppFilter.ALL -> true
    VpnScenarioAppFilter.SYSTEM -> system == true
    VpnScenarioAppFilter.USER -> system == false
}

@StringRes
internal fun vpnScenarioStatusLabel(mode: VpnRoutingMode): Int = when (mode) {
    VpnRoutingMode.WHOLE_DEVICE -> R.string.vpn_scenario_status_whole
    VpnRoutingMode.SELECTED_APPS -> R.string.vpn_scenario_status_selected
}

internal fun vpnScenarioValueStart(label: String): Int {
    var start = label.indexOfFirst { it == ':' || it == '：' } + 1
    while (start < label.length && label[start].isWhitespace()) start++
    return start
}

internal fun vpnScenarioColor(mode: VpnRoutingMode, light: Boolean): Int = when (mode) {
    VpnRoutingMode.WHOLE_DEVICE -> if (light) 0xFF27723B.toInt() else 0xFF7FB34A.toInt()
    VpnRoutingMode.SELECTED_APPS -> if (light) 0xFF23688D.toInt() else 0xFF68B7DC.toInt()
}
