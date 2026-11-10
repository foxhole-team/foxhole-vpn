package com.foxhole.vpn.engine

import com.foxhole.core.model.FoxCoreTunPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress

/** A resolver endpoint, never a fallback or an egress-IP assertion. */
internal data class WireGuardDnsProofPlan(val resolver: InetAddress, val advertisedResolvers: Set<InetAddress>)

internal data class DnsProofLiveRoute(val network: InetAddress, val prefix: Int, val unicast: Boolean)
internal data class DnsProofContextStamp(val ownedHandle: Long, val underlyingHandle: Long,
    val interfaceName: String, val coveringRoutes: Set<String>)

internal fun wireGuardDnsLiveContext(plan: WireGuardDnsProofPlan, ownedHandle: Long, observedUnderlyingHandle: Long,
    expectedInterface: String, liveInterface: String?, liveDns: Collection<InetAddress>,
    routes: List<DnsProofLiveRoute>, localAddresses: Collection<InetAddress>): DnsProofContextStamp {
    require(ownedHandle > 0 && observedUnderlyingHandle > 0 && expectedInterface.isNotBlank() && liveInterface == expectedInterface)
    require(liveDns.toSet() == plan.advertisedResolvers && plan.resolver !in localAddresses) {
        "Current DNS or resolver self-address differs"
    }
    val covering = routes.filter { proofRouteContains(it.network, it.prefix, plan.resolver) }
    val prefix = requireNotNull(covering.maxOfOrNull { it.prefix }) { "Current resolver route missing" }
    val selected = covering.filter { it.prefix == prefix }
    require(selected.all { it.unicast }) { "Current resolver route is not unicast" }
    return DnsProofContextStamp(ownedHandle, observedUnderlyingHandle, expectedInterface, selected.map {
        it.network.address.joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } + "/${it.prefix}"
    }.toSet())
}

internal fun wireGuardDnsProofPlan(protocols: Set<String>, engineJson: String, tun: FoxCoreTunPlan,
    routing: VpnRoutingPolicy, ownerPackage: String, localInterfaceAddresses: Collection<InetAddress>): WireGuardDnsProofPlan? {
    if (protocols != setOf("wireguard") || (routing.webRules.isEmpty() && routing.blockedApplications.isEmpty())) return null
    val root = Json.parseToJsonElement(engineJson).jsonObject
    require(root.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content == "wireguard")
    val applications = applicationRoutingPlan(routing.applicationScope, ownerPackage)
    require(ownerPackage !in routing.blockedApplications && ownerPackage !in applications.disallowed &&
        (applications.allowed.isEmpty() || ownerPackage in applications.allowed)) { "VPN proof owner must remain captured" }
    val dns = root.getValue("dns").jsonObject
    require(dns.keys == setOf("mode", "packet_resolvers")) { "Raw managed profile DNS required for tunnel proof" }
    require(dns.getValue("mode").jsonPrimitive.content == "real_ip")
    val values = requireNotNull(dns["packet_resolvers"] as? JsonArray)
    // The translator currently emits one exact provider resolver. No arbitrary selection.
    require(values.size == 1) { "One actual profile packet resolver required for proof" }
    val resolver = proofNumericAddress(values.single().jsonPrimitive.content)
    require(!resolver.isLoopbackAddress && !resolver.isLinkLocalAddress && !resolver.isMulticastAddress &&
        !resolver.isAnyLocalAddress && resolver.hostAddress != "255.255.255.255") { "Unsafe profile proof resolver" }
    val locals = listOfNotNull(tun.ipv4Address, tun.ipv6Address).map(::proofNumericAddress)
    require((locals + localInterfaceAddresses).none { it == resolver }) { "A self-interface address cannot prove tunnel reachability" }
    val advertised = tun.advertisedDnsServers.map(::proofNumericAddress)
    require(advertised == listOf(resolver)) { "Actual advertised profile DNS required" }
    require(tun.routes.any { route -> proofRouteContains(proofNumericAddress(route.address), route.prefixLength, resolver) }) {
        "Profile resolver must be captured by the installed TUN routes"
    }
    return WireGuardDnsProofPlan(resolver, advertised.toSet())
}

internal fun proofNumericAddress(value: String): InetAddress {
    require(value.isNotEmpty() && '%' !in value && '[' !in value && ']' !in value)
    if (':' in value) require(value.all { it in "0123456789abcdefABCDEF:." })
    else {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 &&
            part.all { it in '0'..'9' } && part.toInt() in 0..255 && (part.length == 1 || !part.startsWith('0')) })
    }
    // The lexical gates above permit IP literals only, never a hostname lookup.
    return InetAddress.getByName(value)
}

internal fun proofRouteContains(network: InetAddress, prefix: Int, address: InetAddress): Boolean {
    val source = network.address
    val target = address.address
    if (source.size != target.size || prefix !in 0..source.size * 8) return false
    for (index in source.indices) {
        val bits = (prefix - index * 8).coerceIn(0, 8)
        val mask = if (bits == 0) 0 else (0xff shl (8 - bits)) and 0xff
        if ((source[index].toInt() and mask) != (target[index].toInt() and mask)) return false
    }
    return true
}
