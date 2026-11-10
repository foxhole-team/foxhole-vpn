package com.foxhole.vpn.engine

import org.junit.Assert.*
import org.junit.Test

class VpnWebsiteMasksTest {
    @Test fun internationalHostnamesKeepBrowserCompatibleNontransitionalIdentity() {
        assertEquals("xn--fa-hia.de", normalizeVpnWebsiteMask("faß.de"))
        assertEquals("*.xn--fa-hia.de", normalizeVpnWebsiteMask("*.faß.de"))
        assertEquals("xn--fa-hia.de", normalizeVpnWebsiteMask("https://faß.de/path"))
        assertEquals("xn--fa-hia.de", normalizeVpnWebsiteMask("xn--fa-hia.de"))
    }

    @Test fun zonesAndSuffixAliasesUseTheSameCanonicalRule() {
        listOf("ru", ".RU", "*.ru", "zone:ru", "suffix:ru").forEach {
            assertEquals(it, "*.ru", normalizeVpnWebsiteMask(it))
        }
        listOf("domain:Example.COM", "suffix:example.com", "+.example.com").forEach {
            assertEquals(it, "*.example.com", normalizeVpnWebsiteMask(it))
        }
        assertEquals("*.xn--p1ai", normalizeVpnWebsiteMask("рф"))
        assertEquals("*.co.uk", normalizeVpnWebsiteMask("*.co.uk"))
        assertEquals("example.com", normalizeVpnWebsiteMask("full:EXAMPLE.COM"))
    }

    @Test fun pastedWebAddressesSelectTheirHostWithoutDnsLookups() {
        listOf("example.com/path?q=1", "//EXAMPLE.com:443/path", "example.com:8443/path",
            "HTTPS://EXAMPLE.COM/path", "https://example.com:443/path#fragment").forEach {
            assertEquals(it, "example.com", normalizeVpnWebsiteMask(it))
        }
        assertEquals("xn--e1afmkfd.xn--p1ai", normalizeVpnWebsiteMask("пример.рф/путь"))
        assertEquals("cidr:192.0.2.1/32", normalizeVpnWebsiteMask("192.0.2.1:443/path"))
        assertEquals("cidr:2001:db8:0:0:0:0:0:1/128", normalizeVpnWebsiteMask("[2001:db8::1]:443/path"))
    }

    @Test fun malformedOrAmbiguousInputsCannotBecomeBroaderRules() {
        listOf("range:example.com", "range:192.0.2.1", "https://name:secret@example.com/",
            "https://example.com:", "https://example.com:0", "https://example.com:65536",
            "https://example.com/a\nb", "example.com/ path", "*.example.*", "ex*ample.com",
            "*", "full:ru", "zone:", "domain:192.0.2.1", "ftp://example.com/path").forEach {
            assertNull(it, normalizeVpnWebsiteMask(it))
        }
    }

    @Test fun canonicalRulesStayStableAcrossRepeatedLoads() {
        listOf("*.ru", "*.xn--p1ai", "example.com", "cidr:192.0.2.0/24",
            "range:192.0.2.3-192.0.2.9", "cidr:2001:db8:0:0:0:0:0:0/32").forEach {
            assertEquals(it, it, normalizeVpnWebsiteMask(it))
        }
    }
    @Test fun websiteHelpFormatsUseTheSharedCanonicalParserFixture() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/website-mask-parity.tsv"))
        stream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val (input, encoded) = line.split('\t', limit = 2)
            assertEquals(input, encoded.takeUnless { it == "<invalid>" }, normalizeVpnWebsiteMask(input))
        } }
    }
    @Test fun rawInputLimitAppliesBeforeTrimmingOrParsing() {
        assertNull(normalizeVpnWebsiteMask(" ".repeat(2048) + "example.com"))
        assertNull(normalizeVpnWebsiteMask("a".repeat(2049)))
    }
}
