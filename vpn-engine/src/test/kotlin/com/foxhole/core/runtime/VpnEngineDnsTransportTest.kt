package com.foxhole.core.runtime

import com.foxhole.vpn.profile.VpnProfileImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnEngineDnsTransportTest {
    private val profile = VpnProfileImporter().import(
        "vless://11111111-1111-4111-8111-111111111111@203.0.113.10:443?security=tls&sni=tls.example#Fixture"
    ).profiles.single()

    @Test fun literalDohAddressIsRetainedSeparatelyFromCertificateName() {
        val upstream = prepare("https", "resolver.example", "203.0.113.53", 18443)
        assertEquals("https://resolver.example:18443/dns-query", upstream.getValue("url").jsonPrimitive.content)
        assertEquals("203.0.113.53", upstream.getValue("server_ip").jsonPrimitive.content)
    }

    @Test fun dotUsesVerifiedHostnameAndExplicitLiteralAddress() {
        val upstream = prepare("tls", "resolver.example", "2001:db8::53", 1853)
        assertEquals("dot", upstream.getValue("type").jsonPrimitive.content)
        assertEquals("resolver.example", upstream.getValue("host").jsonPrimitive.content)
        assertEquals("2001:db8::53", upstream.getValue("server_ip").jsonPrimitive.content)
    }

    @Test fun quicUsesNativeDoqWithoutChangingTheSelectedRoute() {
        val dns = preparedDns("quic", "resolver.example", "203.0.113.53", 1853)
        assertEquals("primary", dns.getValue("route").jsonPrimitive.content)
        val upstream = (dns.getValue("upstreams") as JsonArray).single().jsonObject
        assertEquals("doq", upstream.getValue("type").jsonPrimitive.content)
        assertEquals("resolver.example", upstream.getValue("host").jsonPrimitive.content)
        assertEquals("1853", upstream.getValue("port").jsonPrimitive.content)
        assertEquals("203.0.113.53", upstream.getValue("server_ip").jsonPrimitive.content)
    }

    @Test fun h3RetainsHttpsIdentityAndLiteralConnectionAddress() {
        val upstream = prepare("h3", "resolver.example", "2001:db8::53", 18443)
        assertEquals("doh3", upstream.getValue("type").jsonPrimitive.content)
        assertEquals("https://resolver.example:18443/dns-query", upstream.getValue("url").jsonPrimitive.content)
        assertEquals("2001:db8::53", upstream.getValue("server_ip").jsonPrimitive.content)
    }

    @Test fun doqCannotUseTheForbiddenDnsPortOrInvalidConnectionIp() {
        assertThrows(RuntimeException::class.java) { prepare("quic", "resolver.example", "203.0.113.53", 53) }
        assertThrows(RuntimeException::class.java) { prepare("quic", "resolver.example", "not-an-ip", 853) }
    }

    @Test fun invalidAddressAndUnsupportedDnsTransportAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { prepare("https", "resolver.example", "not-an-ip", 18443) }
        assertThrows(IllegalStateException::class.java) { prepare("dnscrypt", "203.0.113.53", null, 853) }
    }

    @Test fun packetTunnelRefusesExplicitQuicDnsInsteadOfUsingProviderDns() {
        for (type in listOf("quic", "h3", "QUIC", "H3")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                VpnEngineConfig.prepare(packetProfile(type, "dns-remote"))
            }
            assertEquals("unsupported_packet_tunnel_dns_transport", failure.message)
        }
    }

    @Test fun packetTunnelKeepsExplicitProviderDnsWhenNewRemoteChoiceIsInactive() {
        for (type in listOf("quic", "h3")) {
            val prepared = VpnEngineConfig.prepare(packetProfile(type, "dns-wireguard"))
            assertEquals(listOf("10.1.2.3"), prepared.tun.advertisedDnsServers)
            val dns = Json.parseToJsonElement(prepared.engineJson).jsonObject.getValue("dns").jsonObject
            assertEquals("real_ip", dns.getValue("mode").jsonPrimitive.content)
            assertFalse(dns.containsKey("upstreams"))
        }
    }

    private fun packetProfile(type: String, finalTag: String) = run {
        val packet = VpnProfileImporter().import(
            "wireguard://fixture-private@8.8.8.8:51820?publickey=fixture-public&address=10.8.0.2%2F32&dns=10.1.2.3#Fixture"
        ).profiles.single()
        val root = Json.parseToJsonElement(packet.normalizedConfigJson).jsonObject
        val dns = root.getValue("dns").jsonObject
        val remote = buildJsonObject {
            put("tag", "dns-remote"); put("type", type); put("server", "203.0.113.53")
            put("server_port", if (type.lowercase() == "quic") 853 else 443)
            if (type.lowercase() == "h3") put("path", "/dns-query")
            put("detour", "proxy")
        }
        val servers = (dns.getValue("servers") as JsonArray).map { server ->
            if (server.jsonObject["tag"]?.jsonPrimitive?.content == "dns-remote") remote else server
        }
        val changedDns = JsonObject(dns + mapOf("servers" to JsonArray(servers), "final" to JsonPrimitive(finalTag)))
        packet.copy(normalizedConfigJson = JsonObject(root + ("dns" to changedDns)).toString())
    }

    private fun prepare(type: String, host: String, ip: String?, port: Int): JsonObject =
        (preparedDns(type, host, ip, port).getValue("upstreams") as JsonArray).single().jsonObject

    private fun preparedDns(type: String, host: String, ip: String?, port: Int): JsonObject {
        val root = Json.parseToJsonElement(profile.normalizedConfigJson).jsonObject
        val server = buildJsonObject {
            put("type", type); put("tag", "dns-remote"); put("server", host); put("server_port", port); put("detour", "proxy")
            if (type in setOf("https", "h3")) put("path", "/dns-query")
            ip?.let { put("server_ip", it) }
        }
        val dns = JsonObject(root.getValue("dns").jsonObject + ("servers" to JsonArray(listOf(server))))
        val changed = profile.copy(normalizedConfigJson = JsonObject(root + ("dns" to dns)).toString())
        val prepared = Json.parseToJsonElement(VpnEngineConfig.prepare(changed).engineJson).jsonObject
        return prepared.getValue("dns").jsonObject
    }
}
