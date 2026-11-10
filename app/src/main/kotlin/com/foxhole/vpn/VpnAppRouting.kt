package com.foxhole.vpn

import com.foxhole.vpn.engine.VpnApplicationScope
import com.foxhole.vpn.engine.VpnRoutingPolicy
import com.foxhole.vpn.engine.VpnWebsiteRule
import com.foxhole.vpn.engine.vpnWebsiteNativeRuleCount
import kotlinx.serialization.Serializable

@Serializable
enum class VpnRoutingMode { WHOLE_DEVICE, SELECTED_APPS }

@Serializable
enum class VpnAppRoute { VPN, DIRECT, BLOCK }

@Serializable
data class VpnAppRule(val packageName: String, val route: VpnAppRoute = VpnAppRoute.VPN)

@Serializable
data class VpnWebRule(val mask: String, val route: VpnAppRoute = VpnAppRoute.VPN)

@Serializable
data class VpnAppRouting(
    val mode: VpnRoutingMode = VpnRoutingMode.WHOLE_DEVICE,
    val defaultThroughVpn: Boolean = true,
    val rules: List<VpnAppRule> = emptyList(),
    val atomicApply: Boolean = true,
    val confirmChanges: Boolean = false,
    val webRules: List<VpnWebRule> = emptyList(),
)

internal fun VpnAppRouting.validated(ownerPackage: String): VpnAppRouting {
    require(rules.size <= 4_096 && rules.map { it.packageName }.distinct().size == rules.size)
    require(rules.all { it.packageName != ownerPackage && APP_PACKAGE.matches(it.packageName) })
    require(webRules.size <= 4_096 && webRules.map { it.mask }.distinct().size == webRules.size)
    require(webRules.all { normalizeVpnWebMask(it.mask) == it.mask })
    require(webRules.sumOf { vpnWebsiteNativeRuleCount(it.mask) } <= 4_096)
    return this
}

internal fun VpnAppRouting.applicationScope(ownerPackage: String): VpnApplicationScope {
    validated(ownerPackage)
    return when {
        mode == VpnRoutingMode.WHOLE_DEVICE -> VpnApplicationScope.WholeDevice
        defaultThroughVpn -> rules.filter { it.route == VpnAppRoute.DIRECT }.map { it.packageName }.toSet().let {
            if (it.isEmpty()) VpnApplicationScope.WholeDevice else VpnApplicationScope.Exclude(it)
        }
        else -> VpnApplicationScope.Include(rules.filter { it.route != VpnAppRoute.DIRECT }.map { it.packageName }.toSet())
    }
}

internal fun VpnAppRouting.withRule(packageName: String, route: VpnAppRoute): VpnAppRouting = copy(
    rules = if (rules.any { it.packageName == packageName }) rules.map { if (it.packageName == packageName) it.copy(route = route) else it }
        else rules + VpnAppRule(packageName, route),
)

internal fun VpnAppRouting.enginePolicy(ownerPackage: String): VpnRoutingPolicy = VpnRoutingPolicy(
    applicationScope(ownerPackage), webRules.map { VpnWebsiteRule(it.mask, it.route == VpnAppRoute.VPN, it.route == VpnAppRoute.BLOCK) },
    if (mode == VpnRoutingMode.SELECTED_APPS) rules.filter { it.route == VpnAppRoute.BLOCK }.map { it.packageName }.toSet() else emptySet(),
)

internal fun VpnAppRouting.withWebRule(mask: String, route: VpnAppRoute): VpnAppRouting {
    val normalized = requireNotNull(normalizeVpnWebMask(mask)) { "Invalid website routing mask" }
    return copy(webRules = if (webRules.any { it.mask == normalized })
        webRules.map { if (it.mask == normalized) it.copy(route = route) else it }
        else webRules + VpnWebRule(normalized, route))
}


internal fun routingChangeNeedsConfirmation(before: VpnAppRouting, after: VpnAppRouting): Boolean =
    (before.confirmChanges || !before.atomicApply) &&
        (before.mode != after.mode || before.defaultThroughVpn != after.defaultThroughVpn)

private val APP_PACKAGE = Regex("^[A-Za-z0-9._]{1,255}$")
