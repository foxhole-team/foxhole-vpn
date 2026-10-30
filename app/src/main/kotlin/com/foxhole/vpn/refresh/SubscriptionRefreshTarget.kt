package com.foxhole.vpn.refresh

import com.foxhole.vpn.profile.VpnProfile

internal data class SubscriptionRefreshTarget(val source: String, val profiles: List<VpnProfile>) {
    val key: String = subscriptionSourceKey(source)
    val allowInsecureTls: Boolean = profiles.isNotEmpty() && profiles.all(VpnProfile::insecureTlsConsent)
    override fun toString(): String = "SubscriptionRefreshTarget(source=<redacted>, profiles=${profiles.size})"
}
