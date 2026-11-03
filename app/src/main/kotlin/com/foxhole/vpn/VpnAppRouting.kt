package com.foxhole.vpn

import com.foxhole.vpn.engine.VpnApplicationScope
import kotlinx.serialization.Serializable

@Serializable
enum class VpnRoutingMode { WHOLE_DEVICE, SELECTED_APPS }

@Serializable
enum class VpnAppRoute { VPN, DIRECT }

@Serializable
data class VpnAppRule(val packageName: String, val route: VpnAppRoute = VpnAppRoute.VPN)

@Serializable
data class VpnAppRouting(
    val mode: VpnRoutingMode = VpnRoutingMode.WHOLE_DEVICE,
    val defaultThroughVpn: Boolean = true,
    val rules: List<VpnAppRule> = emptyList(),
    val atomicApply: Boolean = true,
    val confirmChanges: Boolean = false,
)

internal fun VpnAppRouting.validated(ownerPackage: String): VpnAppRouting {
    require(rules.size <= 4_096 && rules.map { it.packageName }.distinct().size == rules.size)
    require(rules.all { it.packageName != ownerPackage && APP_PACKAGE.matches(it.packageName) })
    return this
}

internal fun VpnAppRouting.applicationScope(ownerPackage: String): VpnApplicationScope {
    validated(ownerPackage)
    return when {
        mode == VpnRoutingMode.WHOLE_DEVICE -> VpnApplicationScope.WholeDevice
        defaultThroughVpn -> rules.filter { it.route == VpnAppRoute.DIRECT }.map { it.packageName }.toSet().let {
            if (it.isEmpty()) VpnApplicationScope.WholeDevice else VpnApplicationScope.Exclude(it)
        }
        else -> VpnApplicationScope.Include(rules.filter { it.route == VpnAppRoute.VPN }.map { it.packageName }.toSet())
    }
}

internal fun VpnAppRouting.withRule(packageName: String, route: VpnAppRoute): VpnAppRouting = copy(
    rules = if (rules.any { it.packageName == packageName }) rules.map { if (it.packageName == packageName) it.copy(route = route) else it }
        else rules + VpnAppRule(packageName, route),
)


internal fun routingChangeNeedsConfirmation(before: VpnAppRouting, after: VpnAppRouting): Boolean =
    (before.confirmChanges || !before.atomicApply) &&
        (before.mode != after.mode || before.defaultThroughVpn != after.defaultThroughVpn)

private val APP_PACKAGE = Regex("^[A-Za-z0-9._]{1,255}$")
