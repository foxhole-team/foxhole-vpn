package com.foxhole.vpn.engine

data class VpnWebsiteRule(val mask: String, val throughVpn: Boolean, val blocked: Boolean = false)

/** Destination rules apply to the applications captured by the chosen scenario. */
data class VpnRoutingPolicy(
    val applicationScope: VpnApplicationScope = VpnApplicationScope.WholeDevice,
    val webRules: List<VpnWebsiteRule> = emptyList(),
    val blockedApplications: Set<String> = emptySet(),
)
