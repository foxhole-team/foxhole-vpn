package com.foxhole.vpn.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VpnNativeWebsitePolicyTest {
    private val source = """{"dns":{"mode":"fake_ip"},"traffic":{"default_action":"vpn"}}"""

    @Test fun unvalidatedMasksCannotCrossTheNativePolicyBoundary() {
        listOf("range:example.com", "*.example.*", "domain:example.com", "EXAMPLE.COM").forEach { mask ->
            assertThrows(mask, IllegalArgumentException::class.java) {
                VpnNativeApplicationPolicy(source, listOf(VpnWebsiteRule(mask, false)))
            }
        }
    }

    @Test fun encodesExactSuffixAndNetworkRulesWithTheirSelectedRoute() {
        val policy = VpnNativeApplicationPolicy(source, listOf(
            VpnWebsiteRule("example.com", true), VpnWebsiteRule("*.org", false),
            VpnWebsiteRule("cidr:192.0.2.0/24", false)))
        val rules = Json.parseToJsonElement(policy.engine()).jsonObject.getValue("routes").jsonArray
        assertEquals("example.com", rules[0].jsonObject.getValue("exact_domains").jsonArray.single().jsonPrimitive.content)
        assertEquals("default", rules[0].jsonObject.getValue("action").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("org", rules[1].jsonObject.getValue("domain_suffixes").jsonArray.single().jsonPrimitive.content)
        assertEquals("direct", rules[1].jsonObject.getValue("action").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("192.0.2.0/24", rules[2].jsonObject.getValue("cidrs").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun nativeGlobalHoldRemainsPresentWithDirectWebsiteRules() {
        val policy = VpnNativeApplicationPolicy(source, listOf(VpnWebsiteRule("*.example.com", false)))
        val blocked = Json.parseToJsonElement(policy.policy(true, 7)).jsonObject
        assertTrue(blocked.getValue("traffic").jsonObject.getValue("kill_switch").jsonPrimitive.boolean)
        assertEquals(7, blocked.getValue("expected_revision").jsonPrimitive.int)
        assertEquals(1, blocked.getValue("routes").jsonArray.size)
        assertFalse(Json.parseToJsonElement(policy.policy(false, 8)).jsonObject.getValue("traffic").jsonObject.getValue("kill_switch").jsonPrimitive.boolean)
    }

    @Test fun blockedSitesAndAppsUseNativeBlockActions() {
        val policy = VpnNativeApplicationPolicy(source, listOf(VpnWebsiteRule("*.example.com", false, true)), setOf("org.example.blocked"))
        val json = Json.parseToJsonElement(policy.engine()).jsonObject
        assertEquals("block", json.getValue("routes").jsonArray.single().jsonObject.getValue("action").jsonObject.getValue("type").jsonPrimitive.content)
        val app = json.getValue("traffic").jsonObject.getValue("applications").jsonArray.single().jsonObject
        assertEquals("org.example.blocked", app.getValue("package").jsonPrimitive.content)
        assertEquals("block", app.getValue("action").jsonPrimitive.content)
    }

    @Test fun addressRangesCoverExactlyTheirBoundsAndRespectNativeMatcherLimits() {
        assertEquals(listOf("192.0.2.3/32", "192.0.2.4/30", "192.0.2.8/31"), vpnWebsiteRangeCidrs("range:192.0.2.3-192.0.2.9"))
        assertEquals(listOf("0.0.0.0/0"), vpnWebsiteRangeCidrs("range:0.0.0.0-255.255.255.255"))
        val mask = requireNotNull(normalizeVpnWebsiteMask("::1-ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe"))
        val rules = Json.parseToJsonElement(VpnNativeApplicationPolicy(source, listOf(VpnWebsiteRule(mask, false, true))).engine()).jsonObject.getValue("routes").jsonArray
        assertTrue(rules.size > 1)
        assertTrue(rules.all { it.jsonObject.getValue("cidrs").jsonArray.size <= 64 })
        assertEquals(vpnWebsiteRangeCidrs(mask), rules.flatMap { it.jsonObject.getValue("cidrs").jsonArray.map { cidr -> cidr.jsonPrimitive.content } })
    }
}
