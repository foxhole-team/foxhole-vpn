package com.foxhole.vpn.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VpnWebsiteDnsPolicyTest {
    private val source = """{"dns":{"mode":"real_ip","advertise":"1.1.1.1","route":"primary","upstreams":[{"type":"doh","url":"https://resolver.example/dns-query","server_ip":"192.0.2.53"}]},"traffic":{"default_action":"vpn"}}"""

    @Test fun domainRoutingUsesAuthoritativeNamesWithoutChangingTheChosenDnsEgress() {
        val original = Json.parseToJsonElement(source).jsonObject.getValue("dns").jsonObject
        val policy = VpnNativeApplicationPolicy(source, listOf(VpnWebsiteRule("*.org", false)))
        val actual = Json.parseToJsonElement(policy.engine()).jsonObject.getValue("dns").jsonObject
        assertEquals("fake_ip", actual.getValue("mode").jsonPrimitive.content)
        assertEquals(JsonObject(original - "mode"), JsonObject(actual - "mode"))
        assertEquals(actual, Json.parseToJsonElement(policy.policy(false, 4)).jsonObject.getValue("dns"))
    }

    @Test fun blockAndVpnDomainRulesRetainTheSameAuthoritativeDnsMode() {
        listOf(VpnWebsiteRule("example.com", true), VpnWebsiteRule("*.org", false, true)).forEach { rule ->
            val actual = Json.parseToJsonElement(VpnNativeApplicationPolicy(source, listOf(rule)).engine()).jsonObject
            assertEquals("fake_ip", actual.getValue("dns").jsonObject.getValue("mode").jsonPrimitive.content)
        }
    }

    @Test fun numericNetworkRulesKeepOriginalDnsBehavior() {
        val expected = Json.parseToJsonElement(source).jsonObject.getValue("dns")
        listOf(emptyList(), listOf(VpnWebsiteRule("cidr:192.0.2.0/24", false)),
            listOf(VpnWebsiteRule("range:192.0.2.3-192.0.2.9", false, true))).forEach {
            val actual = Json.parseToJsonElement(VpnNativeApplicationPolicy(source, it).engine()).jsonObject
            assertEquals(expected, actual.getValue("dns"))
        }
    }

    @Test fun wireGuardPrivateDnsIsNeverReplacedByAProxyResolver() {
        val packet = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip","packet_resolvers":["10.8.0.1"]},"traffic":{"default_action":"vpn"}}"""
        val original = Json.parseToJsonElement(packet).jsonObject.getValue("dns")
        val policy = VpnNativeApplicationPolicy(packet, listOf(VpnWebsiteRule("*.org", false, true)))
        assertEquals(original, Json.parseToJsonElement(policy.engine()).jsonObject.getValue("dns"))
    }

    @Test fun wireGuardDomainRulesRequireTheActualProfilePacketResolver() {
        val incomplete = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip"},"traffic":{"default_action":"vpn"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            VpnNativeApplicationPolicy(incomplete, listOf(VpnWebsiteRule("*.org", false)))
        }
        val configured = incomplete.replace("\"mode\":\"real_ip\"", "\"mode\":\"real_ip\",\"packet_resolvers\":[\"10.8.0.1\"]")
        val dns = Json.parseToJsonElement(VpnNativeApplicationPolicy(configured, listOf(VpnWebsiteRule("*.org", false))).engine()).jsonObject.getValue("dns").jsonObject
        assertEquals("real_ip", dns.getValue("mode").jsonPrimitive.content)
        assertEquals("10.8.0.1", (dns.getValue("packet_resolvers") as JsonArray).single().jsonPrimitive.content)
        assertFalse(dns.containsKey("upstreams"))
    }

    @Test fun wireGuardManagedPacketDnsIsPresentFromTheFirstNoDomainSession() {
        val source = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip","packet_resolvers":["127.0.0.1"]},"traffic":{"default_action":"vpn"}}"""
        for (rules in listOf(emptyList(), listOf(VpnWebsiteRule("cidr:192.0.2.0/24", false)))) {
            val dns = Json.parseToJsonElement(VpnNativeApplicationPolicy(source, rules).engine()).jsonObject.getValue("dns").jsonObject
            assertEquals(Json.parseToJsonElement(source).jsonObject.getValue("dns"), dns)
        }
    }

    @Test fun wireGuardNoDomainMissingOrEmptyPacketResolverKeepsTheUnmanagedDnsContract() {
        val missing = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip"}}"""
        val empty = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip","packet_resolvers":[]}}"""
        for (source in listOf(missing, empty)) {
            val expected = Json.parseToJsonElement(source).jsonObject.getValue("dns")
            for (rules in listOf(emptyList(), listOf(VpnWebsiteRule("cidr:192.0.2.0/24", false)),
                listOf(VpnWebsiteRule("range:192.0.2.3-192.0.2.9", false, true)))) {
                val policy = VpnNativeApplicationPolicy(source, rules)
                assertEquals(expected, Json.parseToJsonElement(policy.engine(false)).jsonObject.getValue("dns"))
                assertEquals(expected, Json.parseToJsonElement(policy.engine(true)).jsonObject.getValue("dns"))
                assertEquals(expected, Json.parseToJsonElement(policy.policy(false, 4)).jsonObject.getValue("dns"))
            }
            for (mask in listOf("example.com", "*.org")) {
                assertThrows(IllegalArgumentException::class.java) {
                    VpnNativeApplicationPolicy(source, listOf(VpnWebsiteRule(mask, false)))
                }
            }
        }
    }

    @Test fun wireGuardDomainActivationRemovalAndBarrierNeverToggleManagedDns() {
        val source = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip","packet_resolvers":["10.8.0.1"]}}"""
        val expected = Json.parseToJsonElement(source).jsonObject.getValue("dns")
        for (rules in listOf(emptyList(), listOf(VpnWebsiteRule("example.com", false)),
            listOf(VpnWebsiteRule("*.org", false, true)), listOf(VpnWebsiteRule("cidr:192.0.2.0/24", true)))) {
            val policy = VpnNativeApplicationPolicy(source, rules, setOf("com.example.blocked"))
            assertEquals(expected, Json.parseToJsonElement(policy.engine(false)).jsonObject.getValue("dns"))
            assertEquals(expected, Json.parseToJsonElement(policy.engine(true)).jsonObject.getValue("dns"))
            assertEquals(expected, Json.parseToJsonElement(policy.policy(true, 7)).jsonObject.getValue("dns"))
            assertEquals(expected, Json.parseToJsonElement(policy.policy(false, 8)).jsonObject.getValue("dns"))
        }
    }

    @Test fun aMissingProfileResolverCannotSilentlyIntroduceAPublicFallback() {
        val incomplete = """{"dns":{"mode":"real_ip"},"traffic":{"default_action":"vpn"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            VpnNativeApplicationPolicy(incomplete, listOf(VpnWebsiteRule("example.com", false)))
        }
    }
}
