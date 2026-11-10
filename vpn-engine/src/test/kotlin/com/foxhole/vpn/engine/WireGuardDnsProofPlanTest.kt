package com.foxhole.vpn.engine

import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.FoxCoreTunRoute
import com.foxhole.core.runtime.VpnEngineConfig
import com.foxhole.vpn.profile.VpnProfileImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class WireGuardDnsProofPlanTest {
    private val owner = "com.foxhole.vpn"
    private fun engine(resolver: String) = """{"outbound":{"type":"wireguard"},"dns":{"mode":"real_ip","packet_resolvers":["$resolver"]}}"""
    private fun tun(resolver: String) = FoxCoreTunPlan(1420, "10.8.0.2", 32, null, null,
        listOf(FoxCoreTunRoute("0.0.0.0", 0)), listOf(resolver))
    private val rules = listOf(VpnWebsiteRule("*.org", false))

    @Test fun exactProfilePrivateDnsIsCapturedAndOwnerSurvivesEachApplicationScope() {
        for (scope in listOf(VpnApplicationScope.WholeDevice, VpnApplicationScope.Include(emptySet()),
            VpnApplicationScope.Exclude(setOf(owner)))) {
            val plan = wireGuardDnsProofPlan(setOf("wireguard"), engine("10.8.0.1"), tun("10.8.0.1"),
                VpnRoutingPolicy(scope, rules), owner, emptySet())
            assertEquals(proofNumericAddress("10.8.0.1"), plan?.resolver)
        }
    }

    @Test fun actualImporterTranslatorAndNativePolicyProduceASupportedProfileDnsPlan() {
        for (dns in listOf("10.1.2.3", "10.1.2.3%2C10.1.2.4")) {
            val profile = VpnProfileImporter().import(
                "wireguard://fixture-private@8.8.8.8:51820?publickey=fixture-public&address=10.8.0.2%2F32&dns=$dns#Fixture"
            ).profiles.single()
            val prepared = VpnEngineConfig.prepare(profile)
            val routing = VpnRoutingPolicy(webRules = rules)
            val engine = VpnNativeApplicationPolicy(prepared.engineJson, routing.webRules).engine()
            val shape = Json.parseToJsonElement(engine).jsonObject.getValue("dns").jsonObject
            assertEquals(setOf("mode", "packet_resolvers"), shape.keys)
            // Current translator selects and advertises the first configured provider DNS.
            // The proof uses that output, not another source-profile resolver as fallback.
            assertEquals(listOf("10.1.2.3"), prepared.tun.advertisedDnsServers)
            val plan = wireGuardDnsProofPlan(prepared.protocols, engine, prepared.tun, routing, owner, emptySet())
            assertEquals(proofNumericAddress("10.1.2.3"), plan?.resolver)
            assertEquals(setOf(proofNumericAddress("10.1.2.3")), plan?.advertisedResolvers)
        }
    }

    @Test fun bestLiveRouteMustBeUnicastAndLiveDnsMustMatchExactly() {
        val resolver = proofNumericAddress("10.8.0.1")
        val plan = WireGuardDnsProofPlan(resolver, setOf(resolver))
        val default = DnsProofLiveRoute(proofNumericAddress("0.0.0.0"), 0, true)
        val throwHost = DnsProofLiveRoute(resolver, 32, false)
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver), listOf(default, throwHost), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver, proofNumericAddress("1.1.1.1")), listOf(default), emptySet())
        }
        val valid = wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver), listOf(default), emptySet())
        assertEquals(10L, valid.underlyingHandle)
    }

    @Test fun observedUnderlyingAndTunChangesInvalidateTheContext() {
        val resolver = proofNumericAddress("10.8.0.1")
        val plan = WireGuardDnsProofPlan(resolver, setOf(resolver))
        val routes = listOf(DnsProofLiveRoute(proofNumericAddress("0.0.0.0"), 0, true))
        val before = wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver), routes, emptySet())
        val changed = wireGuardDnsLiveContext(plan, 1, 11, "tun0", "tun0", listOf(resolver), routes, emptySet())
        assertNotEquals(before, changed)
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun1", listOf(resolver), routes, emptySet())
        }
    }

    @Test fun unrelatedInterfaceAndRouteChangesDoNotChangeAValidStampButSelfResolverRejects() {
        val resolver = proofNumericAddress("10.8.0.1")
        val plan = WireGuardDnsProofPlan(resolver, setOf(resolver))
        val routes = listOf(DnsProofLiveRoute(proofNumericAddress("0.0.0.0"), 0, true))
        val before = wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver), routes, emptySet())
        val unrelated = wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver),
            routes + DnsProofLiveRoute(proofNumericAddress("192.0.2.0"), 24, false), setOf(proofNumericAddress("192.0.2.7")))
        assertEquals(before, unrelated)
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsLiveContext(plan, 1, 10, "tun0", "tun0", listOf(resolver), routes, setOf(resolver))
        }
    }

    @Test fun proxyAndWgWithoutRulesKeepTheirExistingProofSelection() {
        assertNull(wireGuardDnsProofPlan(setOf("vless"), "not parsed", tun("10.8.0.1"), VpnRoutingPolicy(webRules = rules), owner, emptySet()))
        assertNull(wireGuardDnsProofPlan(setOf("wireguard"), "not parsed", tun("10.8.0.1"), VpnRoutingPolicy(), owner, emptySet()))
    }

    @Test fun capturedIpv6ProfileResolverRemainsAnExactLiteralPlan() {
        val address = "2001:db8::53"
        val plan = tun(address).copy(ipv6Address = "2001:db8::2", ipv6PrefixLength = 128,
            routes = listOf(FoxCoreTunRoute("0.0.0.0", 0), FoxCoreTunRoute("::", 0)))
        assertEquals(proofNumericAddress(address), wireGuardDnsProofPlan(setOf("wireguard"), engine(address), plan,
            VpnRoutingPolicy(webRules = rules), owner, emptySet())?.resolver)
    }

    @Test fun unsafeSelfUncapturedAndNonliteralResolversAreRejected() {
        for (address in listOf("127.0.0.1", "169.254.1.1", "224.0.0.1", "0.0.0.0", "255.255.255.255", "10.8.0.2", "::1", "fe80::1", "ff02::1", "::")) {
            assertThrows(IllegalArgumentException::class.java) {
                wireGuardDnsProofPlan(setOf("wireguard"), engine(address), tun(address), VpnRoutingPolicy(webRules = rules), owner, emptySet())
            }
        }
        for (address in listOf("resolver.example", "01.2.3.4", "١٢٧.0.0.1", "fe80::1%wlan0")) {
            assertThrows(IllegalArgumentException::class.java) { proofNumericAddress(address) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsProofPlan(setOf("wireguard"), engine("10.8.0.1"), tun("10.8.0.1").copy(routes = listOf(FoxCoreTunRoute("192.0.2.0", 24))),
                VpnRoutingPolicy(webRules = rules), owner, emptySet())
        }
    }

    @Test fun physicalSelfAddressBlockedOwnerAndDifferentAdvertisedDnsCannotProveTheTunnel() {
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsProofPlan(setOf("wireguard"), engine("10.8.0.1"), tun("10.8.0.1"), VpnRoutingPolicy(webRules = rules),
                owner, setOf(proofNumericAddress("10.8.0.1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsProofPlan(setOf("wireguard"), engine("10.8.0.1"), tun("10.8.0.1"), VpnRoutingPolicy(blockedApplications = setOf(owner)), owner, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsProofPlan(setOf("wireguard"), engine("10.8.0.1"), tun("1.1.1.1"), VpnRoutingPolicy(webRules = rules), owner, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            wireGuardDnsProofPlan(setOf("wireguard"), engine("10.8.0.1").replace("\"mode\":\"real_ip\"", "\"mode\":\"real_ip\",\"upstreams\":[]"),
                tun("10.8.0.1"), VpnRoutingPolicy(webRules = rules), owner, emptySet())
        }
    }
}
