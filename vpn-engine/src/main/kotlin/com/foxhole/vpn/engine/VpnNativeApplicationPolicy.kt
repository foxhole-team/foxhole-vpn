package com.foxhole.vpn.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Native barrier only. Android owns application capture and DNS namespaces. */
internal class VpnNativeApplicationPolicy(engineJson: String, websites: List<VpnWebsiteRule> = emptyList(),
    private val blockedApplications: Set<String> = emptySet()) {
    private val engine = Json.parseToJsonElement(engineJson).jsonObject.let { original ->
        require(websites.all { normalizeVpnWebsiteMask(it.mask) == it.mask }) { "Canonical website masks required" }
        val hasDomainRules = websites.any { !it.mask.startsWith("cidr:") && !it.mask.startsWith("range:") }
        val packetTunnel = original["outbound"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "wireguard"
        val dns = original.getValue("dns").jsonObject
        if (hasDomainRules && packetTunnel) {
            require((dns["packet_resolvers"] as? JsonArray)?.isNotEmpty() == true) {
                "WireGuard website routing requires the profile packet DNS resolver"
            }
        }
        val base = if (hasDomainRules && !packetTunnel && dns["mode"]?.jsonPrimitive?.contentOrNull != "fake_ip") {
            require((dns["upstreams"] as? JsonArray)?.isNotEmpty() == true && dns["advertise"] != null) {
                "Website routing requires the profile DNS upstream and advertised resolver"
            }
            // Reverse DNS may restrict an IP route, but only a fake-IP binding can authorize Direct.
            JsonObject(original + ("dns" to JsonObject(dns + ("mode" to JsonPrimitive("fake_ip")))))
        } else original
        if (websites.isEmpty()) base else JsonObject(base + ("routes" to buildJsonArray {
            require(websites.sumOf { vpnWebsiteNativeRuleCount(it.mask) } <= 4_096)
            websites.forEach { rule ->
              val range = if (rule.mask.startsWith("range:")) vpnWebsiteRangeCidrs(rule.mask).chunked(64) else listOf(listOf(rule.mask.removePrefix("cidr:").removePrefix("*.")))
              range.forEach { values -> add(buildJsonObject {
                val key = when {
                    rule.mask.startsWith("cidr:") || rule.mask.startsWith("range:") -> "cidrs"
                    rule.mask.startsWith("*.") -> "domain_suffixes"
                    else -> "exact_domains"
                }
                put(key, JsonArray(values.map(::JsonPrimitive)))
                put("action", buildJsonObject {
                    put("type", when { rule.blocked -> "block"; rule.throughVpn -> "outbound"; else -> "direct" })
                    if (!rule.blocked && rule.throughVpn) put("id", "default")
                })
            }) } }
        }))
    }
    private val dns = engine.getValue("dns")

    fun engine(blocked: Boolean = false): String = JsonObject(engine + ("traffic" to traffic(blocked))).toString()

    fun policy(blocked: Boolean, revision: Long?): String = buildJsonObject {
        if (revision != null) put("expected_revision", revision)
        put("dns", dns)
        put("routes", engine["routes"] ?: JsonArray(emptyList()))
        put("traffic", traffic(blocked))
    }.toString()

    private fun traffic(blocked: Boolean): JsonObject = buildJsonObject {
        put("default_action", "vpn")
        put("tor_enabled", false)
        put("i2p_enabled", false)
        put("quarantine_new_apps", false)
        put("kill_switch", blocked)
        if (blockedApplications.isNotEmpty()) put("applications", buildJsonArray {
            blockedApplications.sorted().forEach { name -> add(buildJsonObject {
                put("package", name)
                put("action", "block")
            }) }
        })
    }
}
