package com.foxhole.vpn

internal enum class RoutingSuccessNotice { SCENARIO, APP_REMOVED, RULES }

internal fun routingSuccessNotice(before: VpnAppRouting, after: VpnAppRouting, liveConnectionPreserved: Boolean): RoutingSuccessNotice? = when {
    before.mode != after.mode || before.defaultThroughVpn != after.defaultThroughVpn -> RoutingSuccessNotice.SCENARIO
    !liveConnectionPreserved -> null
    before.webRules != after.webRules -> RoutingSuccessNotice.RULES
    before.rules.toSet() == after.rules.toSet() -> null
    before.rules.size == after.rules.size + 1 && after.rules.all { it in before.rules } -> RoutingSuccessNotice.APP_REMOVED
    else -> RoutingSuccessNotice.RULES
}
