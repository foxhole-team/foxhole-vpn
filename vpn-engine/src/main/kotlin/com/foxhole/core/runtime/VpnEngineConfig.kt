package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.importer.normalizeProfileBootstrapDns
import com.foxhole.core.importer.projectProfileBootstrapDns
import com.foxhole.vpn.profile.VpnProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PreparedVpnConfig(val engineJson: String, val tun: FoxCoreTunPlan, val protocols: Set<String>) {
    override fun toString(): String = "PreparedVpnConfig(protocols=$protocols, configuration=<redacted>)"
}

object VpnEngineConfig {
    fun prepare(profile: VpnProfile): PreparedVpnConfig {
        val root = Json.parseToJsonElement(profile.normalizedConfigJson).asFoxCoreObject("$")
        root.requireOnlyKeys(setOf("log", "dns", "inbounds", "outbounds", "endpoints", "route", "bootstrap_dns"), "$")
        val bootstrap = normalizeProfileBootstrapDns(root)
        val inbounds = root.getValue("inbounds").asFoxCoreArray("$.inbounds")
        require(inbounds.size == 1 && inbounds.single().jsonObject["type"]?.jsonPrimitive?.content == "tun")
        val tun = translateFoxCoreTun(root)
        require(tun.allowedApplications.isEmpty() && tun.disallowedApplications.isEmpty())
        require(tun.routes.any { it.address == "0.0.0.0" && it.prefixLength == 0 })
        require(tun.ipv6Address == null || tun.routes.any { it.address == "::" && it.prefixLength == 0 })
        validateRouting(root)
        val sources = (root["outbounds"] as? JsonArray).orEmpty() + (root["endpoints"] as? JsonArray).orEmpty()
        val byTag = sources.map { it.asFoxCoreObject("$.outbounds") }.associateBy { it.requiredString("tag", "$.outbounds") }
        require(byTag.size == sources.size)
        val proxy = requireNotNull(byTag["proxy"])
        val members = if (proxy.requiredString("type", "$.proxy") == "selector") {
            proxy.requireOnlyKeys(setOf("type", "tag", "outbounds", "default"), "$.proxy")
            proxy.stringList("outbounds", "$.proxy").also { require(it.isNotEmpty() && it.size <= 64 && it.distinct().size == it.size) }
        } else listOf("proxy")
        val selected = proxy.optionalString("default", "$.proxy") ?: members.first()
        require(selected in members)
        val nodes = members.map { requireNotNull(byTag[it]) }
        val protocols = nodes.map { it.requiredString("type", "$.outbound") }.toSet()
        require(protocols.all { it in supported })
        val packet = "wireguard" in protocols
        require(!packet || nodes.size == 1)
        byTag.filterKeys { it !in members && it != "proxy" }.values.forEach { extra ->
            require(extra.requiredString("type", "$.outbounds") in setOf("direct", "block"))
            extra.requireOnlyKeys(setOf("tag", "type"), "$.outbounds")
        }
        val converted = nodes.mapIndexed { index, node ->
            if (packet) translateFoxCoreWireGuard(node, "$.endpoints[$index]")
            else translateFoxCoreProxyOutbound(node, "$.outbounds[$index]")
        }
        val primary = if (converted.size == 1) converted.single() else buildJsonObject {
            put("type", "selector")
            put("members", buildJsonArray {
                converted.forEachIndexed { index, node -> add(buildJsonObject {
                    put("id", "member-${index + 1}")
                    put("outbound", node)
                }) }
            })
            put("default", "member-${members.indexOf(selected) + 1}")
        }
        val dns = prepareDns(root, packet)
        val preparedTun = tun.copy(advertisedDnsServers = listOf(dns.second))
        val engine = buildJsonObject {
            put("schema_version", 1)
            put("outbound", primary)
            projectProfileBootstrapDns(bootstrap, listOf(primary))?.let { put("bootstrap_dns", it) }
            put("tun", buildJsonObject {
                put("mtu", preparedTun.mtu)
                put("ipv4", preparedTun.ipv4Address)
                preparedTun.ipv6Address?.let { put("ipv6", it) }
            })
            put("dns", dns.first)
            put("runtime", buildJsonObject { put("worker_threads", 2) })
            put("traffic", buildJsonObject {
                put("default_action", "vpn")
                put("tor_enabled", false)
                put("i2p_enabled", false)
                put("quarantine_new_apps", false)
            })
        }
        return PreparedVpnConfig(engine.toString(), preparedTun, protocols)
    }

    private fun validateRouting(root: JsonObject) {
        val route = root.getValue("route").asFoxCoreObject("$.route")
        route.requireOnlyKeys(setOf("rules", "final", "default_domain_resolver", "auto_detect_interface"), "$.route")
        require(route.optionalString("final", "$.route") == "proxy")
        (route["rules"] as? JsonArray).orEmpty().forEach { value ->
            val rule = value.jsonObject
            val action = rule.optionalString("action", "$.route.rules")
            when (action) {
                "sniff" -> require(rule.keys == setOf("action"))
                "hijack-dns" -> {
                    rule.requireOnlyKeys(setOf("action", "port", "protocol"), "$.route.rules")
                    require(rule["port"]?.jsonPrimitive?.content == "53" || rule["protocol"]?.jsonPrimitive?.content == "dns")
                }
                else -> error("unsupported_vpn_route_policy")
            }
        }
    }

    private fun prepareDns(root: JsonObject, packet: Boolean): Pair<JsonObject, String> {
        val source = root.getValue("dns").asFoxCoreObject("$.dns")
        source.requireOnlyKeys(setOf("servers", "strategy", "final", "reverse_mapping", "independent_cache"), "$.dns")
        val servers = source.getValue("servers").asFoxCoreArray("$.dns.servers").map { it.jsonObject }
        if (packet) {
            val finalTag = source.optionalString("final", "$.dns")
            val explicitQuicDns = finalTag != null && servers.any { server ->
                server["tag"]?.jsonPrimitive?.content == finalTag &&
                    server["type"]?.jsonPrimitive?.content?.lowercase() in setOf("quic", "h3")
            }
            require(!explicitQuicDns) { "unsupported_packet_tunnel_dns_transport" }
            val server = requireNotNull(servers.singleOrNull { it["tag"]?.jsonPrimitive?.content == "dns-wireguard" })
            val address = server.requiredString("server", "$.dns.servers")
            require(address.isFoxCoreIpLiteral())
            return buildJsonObject { put("mode", "real_ip") } to address
        }
        val finalTag = source.requiredString("final", "$.dns")
        val server = requireNotNull(servers.singleOrNull { it["tag"]?.jsonPrimitive?.content == finalTag })
        server.requireOnlyKeys(setOf("tag", "type", "server", "server_port", "path", "detour", "server_ip"), "$.dns.servers")
        require(server.optionalString("detour", "$.dns.servers") == "proxy")
        val host = server.requiredString("server", "$.dns.servers")
        val type = server.requiredString("type", "$.dns.servers")
        val port = server.optionalInt("server_port", "$.dns.servers") ?: when (type) {
            "https", "h3" -> 443
            "tls", "quic" -> 853
            else -> 53
        }
        val serverIp = server.optionalString("server_ip", "$.dns.servers")?.also { require(it.isFoxCoreIpLiteral()) }
        require(port in 1..65535 && (type != "quic" || port != 53))
        val upstream = buildJsonObject {
            when (type) {
                "https", "h3" -> {
                    put("type", if (type == "h3") "doh3" else "doh")
                    val escapedHost = if (host.contains(':')) "[$host]" else host
                    put("url", "https://$escapedHost:$port${server.optionalString("path", "$.dns.servers") ?: "/dns-query"}")
                    serverIp?.let { put("server_ip", it) }
                }
                "tls", "quic" -> {
                    require(!server.containsKey("path"))
                    put("type", if (type == "quic") "doq" else "dot")
                    put("host", host)
                    put("port", port)
                    serverIp?.let { put("server_ip", it) }
                }
                "udp", "tcp" -> {
                    require(host.isFoxCoreIpLiteral())
                    put("type", type)
                    put("address", if (host.contains(':')) "[$host]:$port" else "$host:$port")
                }
                else -> error("unsupported_vpn_dns")
            }
        }
        return buildJsonObject {
            put("mode", "real_ip")
            put("advertise", "1.1.1.1")
            put("route", "primary")
            put("upstreams", JsonArray(listOf(upstream)))
        } to "1.1.1.1"
    }

    private val supported = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "naive", "anytls", "wireguard")
}
