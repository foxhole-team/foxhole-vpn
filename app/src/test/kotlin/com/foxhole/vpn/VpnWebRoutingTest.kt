package com.foxhole.vpn

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VpnWebRoutingTest {
    @Test fun savedApplicationSettingsReceiveAnEmptyWebsiteList() {
        val routing = Json.decodeFromString<VpnAppRouting>("""{"mode":"SELECTED_APPS","defaultThroughVpn":false,"rules":[{"packageName":"org.example.saved","route":"VPN"}]}""")
        assertTrue(routing.webRules.isEmpty())
        assertEquals("org.example.saved", routing.rules.single().packageName)
        assertFalse(routing.defaultThroughVpn)
    }

    @Test fun websiteRulesRemainActiveInWholeDeviceMode() {
        val routing = VpnAppRouting(rules = listOf(VpnAppRule("org.example.saved", VpnAppRoute.DIRECT)))
            .withWebRule("*.example.com", VpnAppRoute.DIRECT)
        val policy = routing.enginePolicy("com.foxhole.vpn")
        assertEquals(com.foxhole.vpn.engine.VpnApplicationScope.WholeDevice, policy.applicationScope)
        assertEquals("*.example.com", policy.webRules.single().mask)
        assertFalse(policy.webRules.single().throughVpn)
    }

    @Test fun websiteChangesInvalidatePolicyEvenWhenApplicationCaptureDoesNotChange() {
        val before = VpnAppRouting()
        val after = before.withWebRule("example.com", VpnAppRoute.DIRECT)
        assertEquals(before.applicationScope("com.foxhole.vpn"), after.applicationScope("com.foxhole.vpn"))
        assertNotEquals(before.enginePolicy("com.foxhole.vpn"), after.enginePolicy("com.foxhole.vpn"))
    }

    @Test fun duplicateCanonicalMaskUpdatesOneRuleAndPreservesOrder() {
        val routing = VpnAppRouting().withWebRule("EXAMPLE.COM", VpnAppRoute.VPN)
            .withWebRule("*.org", VpnAppRoute.DIRECT).withWebRule("example.com.", VpnAppRoute.DIRECT)
        assertEquals(listOf("example.com", "*.org"), routing.webRules.map { it.mask })
        assertEquals(VpnAppRoute.DIRECT, routing.webRules.first().route)
    }

    @Test fun normalizesAddressesAndMasksWithoutKeepingUrlCredentialsOrPaths() {
        assertEquals("example.com", normalizeVpnWebMask("https://EXAMPLE.com/path?q=1"))
        assertEquals("*.com", normalizeVpnWebMask(" .com "))
        assertEquals("xn--e1afmkfd.xn--p1ai", normalizeVpnWebMask("пример.рф"))
        assertEquals("cidr:192.0.2.0/24", normalizeVpnWebMask("cidr:192.0.2.0/24"))
        assertNull(normalizeVpnWebMask("https://name:secret@example.com/"))
        assertNull(normalizeVpnWebMask("bad..example.com"))
        assertNull(normalizeVpnWebMask("*.bad-.com"))
        assertNull(normalizeVpnWebMask("cidr:192.0.2.1/99"))
    }

    @Test fun ipRangesAreNumericOrderedAndWithinOneFamily() {
        assertEquals("range:192.0.2.3-192.0.2.250", normalizeVpnWebMask("192.0.2.3 - 192.0.2.250"))
        assertEquals("cidr:192.0.2.0/24", normalizeVpnWebMask("192.0.2.99/24"))
        assertNotNull(normalizeVpnWebMask("2001:db8::1-2001:db8::ffff"))
        assertNull(normalizeVpnWebMask("192.0.2.250-192.0.2.3"))
        assertNull(normalizeVpnWebMask("192.0.2.1-2001:db8::1"))
        assertNull(normalizeVpnWebMask("999.1.1.1"))
        assertNull(normalizeVpnWebMask("example.com-192.0.2.1"))
        assertEquals("test-site.example", normalizeVpnWebMask("test-site.example"))
    }

    @Test fun blockedApplicationsAreCapturedInProxyAndIgnoredInWholeDevice() {
        val routing = VpnAppRouting(mode = VpnRoutingMode.SELECTED_APPS, defaultThroughVpn = false)
            .withRule("org.example.blocked", VpnAppRoute.BLOCK)
            .withRule("org.example.direct", VpnAppRoute.DIRECT)
            .withWebRule("*.example.com", VpnAppRoute.BLOCK)
        val policy = routing.enginePolicy("com.foxhole.vpn")
        assertEquals(setOf("org.example.blocked"), policy.blockedApplications)
        assertEquals(com.foxhole.vpn.engine.VpnApplicationScope.Include(setOf("org.example.blocked")), policy.applicationScope)
        assertTrue(policy.webRules.single().blocked)
        assertTrue(routing.copy(mode = VpnRoutingMode.WHOLE_DEVICE).enginePolicy("com.foxhole.vpn").blockedApplications.isEmpty())
    }
}
