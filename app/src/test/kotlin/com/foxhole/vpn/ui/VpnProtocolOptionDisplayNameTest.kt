package com.foxhole.vpn.ui

import com.foxhole.vpn.VpnProtocolSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpnProtocolOptionDisplayNameTest {
    @Test
    fun familyOnlyGeneratedNamesUseTheKnownProtocolHint() {
        assertEquals("FoxHole VLESS", vpnProtocolOptionDisplayName("foxhole-2", "VLESS"))
        assertEquals("VPN Core VMess", vpnProtocolOptionDisplayName("vpn-core-3", "VMESS"))
        assertEquals("foxhole-2", vpnProtocolOptionDisplayName("foxhole-2", "UNKNOWN"))
        assertEquals("Riga-2", vpnProtocolOptionDisplayName("Riga-2", "VLESS"))
    }

    @Test
    fun generatedSubscriptionNamesHideServiceSuffixes() {
        val names = mapOf(
            "vpn-core-vmess-101" to "VPN Core VMess",
            "vpn-core-trojan-102" to "VPN Core Trojan",
            "foxhole-naive-2" to "FoxHole Naive",
            "vpn-core-outline-103" to "VPN Core Outline",
            "vpn-core-shadowsocks2022-104" to "VPN Core Shadowsocks 2022",
        )
        names.forEach { (raw, expected) -> assertEquals(expected, vpnProtocolOptionDisplayName(raw)) }
    }

    @Test
    fun knownProtocolAliasesAndFamilyCapitalizationAreConsistent() {
        val names = mapOf(
            "VPN-CORE-vless-1" to "VPN Core VLESS",
            "vpn-core-ss-1" to "VPN Core Shadowsocks",
            "vpn-core-ss2022-1" to "VPN Core Shadowsocks 2022",
            "foxhole-naiveproxy-1" to "FoxHole Naive",
            "foxhole-hy2-1" to "FoxHole Hysteria2",
            "foxhole-wg-1" to "FoxHole WireGuard",
            "foxhole-anytls-1" to "FoxHole AnyTLS",
            "foxhole-tuic-1" to "FoxHole TUIC",
            "foxhole-awg-1" to "FoxHole AmneziaWG",
        )
        names.forEach { (raw, expected) -> assertEquals(expected, vpnProtocolOptionDisplayName(raw)) }
    }

    @Test
    fun protocolVersionIsNotTreatedAsAServiceSuffix() {
        assertEquals("VPN Core Shadowsocks 2022", vpnProtocolOptionDisplayName("vpn-core-shadowsocks-2022"))
        assertEquals("VPN Core Shadowsocks 2022", vpnProtocolOptionDisplayName("vpn-core-shadowsocks-2022-5"))
        assertEquals("FoxHole Hysteria2", vpnProtocolOptionDisplayName("foxhole-hysteria-2"))
        assertEquals("FoxHole Hysteria2", vpnProtocolOptionDisplayName("foxhole-hysteria-2-5"))
    }

    @Test
    fun recognizedServiceNamesWithoutAnIndexAreAlsoReadable() {
        assertEquals("VPN Core Outline", vpnProtocolOptionDisplayName("vpn-core-outline"))
        assertEquals("FoxHole Naive", vpnProtocolOptionDisplayName("foxhole-naive"))
    }

    @Test
    fun meaningfulUserNamesAndTheirNumbersRemainUnchanged() {
        val names = listOf("Riga 2026", "Work VPN-2", "My vpn-core-vless-1", "VPN Core Riga 12", "VLESS-4", "Home / Outline", "🇱🇻 Riga")
        names.forEach { assertEquals(it, vpnProtocolOptionDisplayName(it)) }
    }

    @Test
    fun unknownProtocolsAndNonServiceSuffixesRemainUnchanged() {
        val names = listOf("vpn-core-custom-9", "foxhole-newproto-3", "vpn-core-vless-backup", "foxhole-naive-2a", "vpn-core-vless-", "foxhole-naive-٢", "")
        names.forEach { assertEquals(it, vpnProtocolOptionDisplayName(it)) }
    }

    @Test
    fun matchingLabelsDoNotReplaceDistinctOptionIdentities() {
        val first = VpnProtocolSummary("first-option", "vpn-core-vless-1", "VLESS")
        val second = VpnProtocolSummary("second-option", "vpn-core-vless-2", "VLESS")
        assertEquals(vpnProtocolOptionDisplayName(first.name), vpnProtocolOptionDisplayName(second.name))
        assertNotEquals(first.id, second.id)
        assertEquals("vpn-core-vless-1", first.name)
        assertEquals("vpn-core-vless-2", second.name)
    }
}
