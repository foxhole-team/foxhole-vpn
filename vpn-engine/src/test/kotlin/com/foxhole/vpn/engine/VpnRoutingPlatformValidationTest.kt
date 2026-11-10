package com.foxhole.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnRoutingPlatformValidationTest {
    @Test fun api29RejectsWireGuardExactAndSuffixDomainsForEveryAction() {
        for (mask in listOf("example.com", "*.org")) {
            for (rule in listOf(VpnWebsiteRule(mask, false), VpnWebsiteRule(mask, true), VpnWebsiteRule(mask, false, true))) {
                val failure = assertThrows(EngineException::class.java) {
                    validateVpnRoutingPlatform(29, setOf("wireguard"), VpnRoutingPolicy(webRules = listOf(rule)))
                }
                assertEquals(EngineFailure.WIREGUARD_DOMAIN_PLATFORM, failure.reason)
            }
        }
    }

    @Test fun api30AllowsWireGuardDomainRules() {
        for (sdk in listOf(30, 36)) {
            validateVpnRoutingPlatform(sdk, setOf("wireguard"),
                VpnRoutingPolicy(webRules = listOf(VpnWebsiteRule("*.org", false))))
        }
    }

    @Test fun api24Through29KeepNoDomainCidrAndRangeWireGuardRouting() {
        for (sdk in 24..29) {
            for (rules in listOf(emptyList(), listOf(VpnWebsiteRule("cidr:192.0.2.0/24", false)),
                listOf(VpnWebsiteRule("range:192.0.2.3-192.0.2.9", false, true)))) {
                validateVpnRoutingPlatform(sdk, setOf("wireguard"), VpnRoutingPolicy(webRules = rules))
            }
        }
    }

    @Test fun api24Through29KeepProxyFakeIpDomainRouting() {
        for (sdk in 24..29) {
            for (protocols in listOf(setOf("vless"), setOf("trojan"), setOf("socks"), setOf("shadowsocks"))) {
                validateVpnRoutingPlatform(sdk, protocols,
                    VpnRoutingPolicy(webRules = listOf(VpnWebsiteRule("*.org", false))))
            }
        }
    }
}
