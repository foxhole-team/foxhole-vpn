package com.foxhole.vpn.engine

/** App-owned settings only; imported profile TUN lists remain forbidden. */
sealed interface VpnApplicationScope {
    data object WholeDevice : VpnApplicationScope
    data class Include(val packages: Set<String>) : VpnApplicationScope
    data class Exclude(val packages: Set<String>) : VpnApplicationScope
}

internal data class ApplicationRoutingPlan(val allowed: List<String>, val disallowed: List<String>)

internal fun applicationRoutingPlan(scope: VpnApplicationScope, ownerPackage: String): ApplicationRoutingPlan {
    val packages = when (scope) {
        VpnApplicationScope.WholeDevice -> emptySet()
        is VpnApplicationScope.Include -> scope.packages
        is VpnApplicationScope.Exclude -> scope.packages
    }
    require(PACKAGE_NAME.matches(ownerPackage) && packages.size <= 4_096 && packages.all(PACKAGE_NAME::matches))
    return when (scope) {
        VpnApplicationScope.WholeDevice -> ApplicationRoutingPlan(emptyList(), emptyList())
        // Android interprets an empty allow-list as all apps. The owner also needs the VPN-bound proof.
        is VpnApplicationScope.Include -> ApplicationRoutingPlan((packages + ownerPackage).sorted(), emptyList())
        is VpnApplicationScope.Exclude -> ApplicationRoutingPlan(emptyList(), (packages - ownerPackage).sorted())
    }
}

private val PACKAGE_NAME = Regex("^[A-Za-z0-9._]{1,255}$")
