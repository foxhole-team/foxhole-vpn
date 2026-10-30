package com.foxhole.vpn.router

import com.foxhole.core.network.isPrivateOrLocalAddress
import com.foxhole.core.network.requirePublicRemoteHost
import com.foxhole.core.runtime.PreparedVpnConfig
import com.foxhole.core.runtime.VpnEngineConfig
import com.foxhole.vpn.profile.VpnProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RouterUpstreamTarget(val address: String, val port: Int, val transport: String)

internal class RouterBootstrapDialUnsupported : RouterApiFailure(RouterApiError.UNSUPPORTED)

internal class RouterDialCandidate(val owner: RouterPhoneSession, val profileSha256: String,
    val prepared: PreparedVpnConfig, val targets: List<RouterUpstreamTarget>) {
    fun leased(claimId: String, expiresAtElapsedMs: Long) = RouterBoundDialPlan(owner, profileSha256, prepared, targets, claimId, expiresAtElapsedMs)
    override fun toString(): String = "RouterDialCandidate"
}

internal class RouterBoundDialPlan(val owner: RouterPhoneSession, val profileSha256: String,
    val prepared: PreparedVpnConfig, val targets: List<RouterUpstreamTarget>, val claimId: String, val expiresAtElapsedMs: Long) {
    override fun toString(): String = "RouterBoundDialPlan"
}

internal fun routerDialPlanCurrent(plan: RouterBoundDialPlan, profile: VpnProfile, revision: Long, now: Long): Boolean =
    profile.id == plan.owner.profileId && revision == plan.owner.profileRevision && routerProfileDigest(profile) == plan.profileSha256 &&
        now >= 0 && now < plan.expiresAtElapsedMs && plan.claimId.matches(Regex("[a-f0-9]{32}"))

internal fun prepareRouterDialCandidate(profile: VpnProfile, owner: RouterPhoneSession,
    resolver: (String) -> List<InetAddress>): RouterDialCandidate {
    require(profile.id == owner.profileId)
    val prepared = VpnEngineConfig.prepare(profile)
    val root = Json.parseToJsonElement(prepared.engineJson) as? JsonObject ?: error("router_dial_shape")
    requireRouterDialBootstrapCompatible(root)
    val targets = linkedSetOf<RouterUpstreamTarget>()
    fun rewrite(node: JsonObject, depth: Int): JsonObject {
        require(depth <= 4)
        val type = (node["type"] as? JsonPrimitive)?.contentOrNull ?: error("router_dial_shape")
        if (type == "selector") {
            val members = node["members"] as? JsonArray ?: error("router_dial_shape")
            require(members.size in 1..32)
            return JsonObject(node + ("members" to JsonArray(members.map { value ->
                val member = value as? JsonObject ?: error("router_dial_shape")
                JsonObject(member + ("outbound" to rewrite(member["outbound"] as? JsonObject ?: error("router_dial_shape"), depth + 1)))
            })))
        }
        require(type in setOf("vless", "vmess", "trojan", "hysteria2", "tuic", "shadowsocks", "wireguard", "naive", "anytls"))
        require(node["port_ranges"] == null && node["port_hopping"] == null)
        val host = (node["server"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("router_dial_shape")
        host.requirePublicRemoteHost(resolveHost = false)
        val port = (node["port"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..65535 } ?: error("router_dial_shape")
        val forced = (node["server_ip"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val addresses = forced?.let { listOf(requireNotNull(routerPublicIpLiteral(it))) }
            ?: routerPublicIpLiteral(host)?.let(::listOf) ?: resolver(host).also { resolved ->
                require(resolved.size in 1..16 && resolved.all { !it.isPrivateOrLocalAddress() })
            }
        val address = requireNotNull(requireNotNull(addresses.sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull()).hostAddress)
        val transports = if (type in setOf("hysteria2", "tuic", "wireguard")) listOf("udp")
            else if (type == "shadowsocks") listOf("tcp", "udp") else listOf("tcp")
        transports.forEach { targets += RouterUpstreamTarget(address, port, it) }
        require(targets.size <= 8)
        return JsonObject(node + ("server_ip" to JsonPrimitive(address)))
    }
    val outbound = rewrite(root["outbound"] as? JsonObject ?: error("router_dial_shape"), 0)
    val forced = PreparedVpnConfig(JsonObject(root + ("outbound" to outbound)).toString(), prepared.tun, prepared.protocols)
    return RouterDialCandidate(owner, routerProfileDigest(profile), forced, targets.toList())
}

internal suspend fun prepareResolvedRouterDialCandidate(profile: VpnProfile, owner: RouterPhoneSession,
    resolver: suspend (String) -> List<InetAddress>, checkOwner: () -> Unit): RouterDialCandidate {
    checkOwner()
    val root = Json.parseToJsonElement(VpnEngineConfig.prepare(profile).engineJson) as? JsonObject ?: error("router_dial_shape")
    requireRouterDialBootstrapCompatible(root)
    val hosts = linkedSetOf<String>()
    val endpoints = linkedSetOf<Triple<String, Int, String>>()
    fun inspect(node: JsonObject, depth: Int) {
        require(depth <= 4)
        val type = (node["type"] as? JsonPrimitive)?.contentOrNull ?: error("router_dial_shape")
        if (type == "selector") {
            val members = node["members"] as? JsonArray ?: error("router_dial_shape")
            require(members.size in 1..32)
            members.forEach { value -> inspect((value as? JsonObject)?.get("outbound") as? JsonObject ?: error("router_dial_shape"), depth + 1) }
            return
        }
        require(type in setOf("vless", "vmess", "trojan", "hysteria2", "tuic", "shadowsocks", "wireguard", "naive", "anytls") &&
            node["port_ranges"] == null && node["port_hopping"] == null)
        val host = (node["server"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("router_dial_shape")
        host.requirePublicRemoteHost(resolveHost = false)
        val port = (node["port"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..65535 } ?: error("router_dial_shape")
        val transports = if (type in setOf("hysteria2", "tuic", "wireguard")) listOf("udp")
            else if (type == "shadowsocks") listOf("tcp", "udp") else listOf("tcp")
        transports.forEach { endpoints += Triple(host, port, it) }
        require(endpoints.size <= 8)
        val forced = (node["server_ip"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (forced != null) requireNotNull(routerPublicIpLiteral(forced))
        else if (routerPublicIpLiteral(host) == null) { routerProviderHostname(host); hosts += host }
    }
    inspect(root["outbound"] as? JsonObject ?: error("router_dial_shape"), 0)
    val resolved = linkedMapOf<String, List<InetAddress>>()
    for (host in hosts) {
        checkOwner(); currentCoroutineContext().ensureActive()
        resolved[host] = resolver(host)
        currentCoroutineContext().ensureActive(); checkOwner()
    }
    return prepareRouterDialCandidate(profile, owner) { host -> requireNotNull(resolved[host]) }.also { checkOwner() }
}

private fun requireRouterDialBootstrapCompatible(root: JsonObject) {
    // IP-specific router leases cannot preserve scoped bootstrap resolution.
    if (root["bootstrap_dns"] is JsonObject) throw RouterBootstrapDialUnsupported()
}

internal fun routerPublicIpLiteral(value: String): InetAddress? {
    val host = value.removePrefix("[").removeSuffix("]")
    if (host.contains(':')) {
        if (!host.matches(Regex("[0-9a-fA-F:]+"))) return null
    } else {
        val fields = host.split('.')
        if (fields.size != 4 || fields.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || it.length > 1 && it[0] == '0' || it.toInt() !in 0..255 }) return null
    }
    return runCatching { InetAddress.getByName(host) }.getOrNull()?.takeUnless { it.isPrivateOrLocalAddress() }
}

internal fun routerProfileDigest(profile: VpnProfile): String = MessageDigest.getInstance("SHA-256")
    .digest(profile.normalizedConfigJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun routerLeaseClaim(seed: ByteArray, candidate: RouterDialCandidate): String = MessageDigest.getInstance("SHA-256")
    .digest(seed + "${candidate.owner.profileId}|${candidate.owner.profileRevision}|${candidate.owner.connectionId}|${candidate.profileSha256}|".toByteArray(Charsets.UTF_8) +
        candidate.targets.joinToString("|") { "${it.address}:${it.port}:${it.transport}" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)

internal fun routerLeaseReleaseOwned(activeOwner: RouterPhoneSession?, expectedOwner: RouterPhoneSession?, stillOwned: Boolean): Boolean =
    stillOwned && (activeOwner == null || activeOwner == expectedOwner)

internal class RouterValidatedDialMemo(val candidate: RouterDialCandidate, private val networkHandle: Long,
    private val address: String, private val validatedAtElapsedMs: Long, private val validForMs: Long = 300000) {
    fun current(owner: RouterPhoneSession, currentNetworkHandle: Long, currentAddress: String, now: Long): Boolean =
        candidate.owner == owner && currentNetworkHandle == networkHandle && currentAddress == address &&
            validatedAtElapsedMs >= 0 && now >= validatedAtElapsedMs && validForMs in 0..300000 && now - validatedAtElapsedMs < validForMs
    override fun toString(): String = "RouterValidatedDialMemo"
}

internal fun routerHandoffCanRelease(status: RouterPhoneStatus, ownedClaim: String?): Boolean = status.handoffKnown &&
    (status.handoff == null || status.handoff.claimId == ownedClaim)
