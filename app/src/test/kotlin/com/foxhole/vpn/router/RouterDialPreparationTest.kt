package com.foxhole.vpn.router

import com.foxhole.vpn.profile.VpnProfileImporter
import com.foxhole.core.runtime.VpnEngineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class RouterDialPreparationTest {
    private fun profile() = VpnProfileImporter().import("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls&sni=example.com&type=ws&path=%2Fws&host=example.com#Fixture").profiles.single()
    private fun domainProfile(): com.foxhole.vpn.profile.VpnProfile {
        val profile = profile()
        val document = Json.parseToJsonElement(profile.normalizedConfigJson).jsonObject
        val nodes = document["outbounds"] as JsonArray
        return profile.copy(normalizedConfigJson = JsonObject(document + ("outbounds" to JsonArray(nodes.map { value ->
            val node = value.jsonObject
            if (node["server"] != null) JsonObject(node + ("server" to JsonPrimitive("vpn.example.com"))) else node
        }))).toString())
    }

    private fun bootstrapProfile(): com.foxhole.vpn.profile.VpnProfile {
        val profile = domainProfile()
        val document = Json.parseToJsonElement(profile.normalizedConfigJson).jsonObject
        val policy = Json.parseToJsonElement("""{"domains":["vpn.example.com"],"upstreams":[{"type":"udp","address":"1.1.1.1:53"}],"timeout_ms":3000,"max_candidates":8}""").jsonObject
        return profile.copy(normalizedConfigJson = JsonObject(document + ("bootstrap_dns" to policy)).toString())
    }

    @Test fun scopedBootstrapRejectsDirectDialPreparationBeforeTheSystemResolver() {
        val profile = bootstrapProfile()
        val original = profile.normalizedConfigJson
        assertTrue(Json.parseToJsonElement(VpnEngineConfig.prepare(profile).engineJson).jsonObject.containsKey("bootstrap_dns"))
        var resolverCalls = 0
        val failure = assertThrows(RouterApiFailure::class.java) {
            prepareRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17)) {
                resolverCalls++
                listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 2)))
            }
        }
        assertEquals(RouterApiError.UNSUPPORTED, failure.kind)
        assertEquals(0, resolverCalls)
        assertEquals(original, profile.normalizedConfigJson)
    }

    @Test fun scopedBootstrapRejectsSuspendingPreparationBeforeTheProviderResolver() = runTest {
        val profile = bootstrapProfile()
        val original = profile.normalizedConfigJson
        var resolverCalls = 0
        var ownerChecks = 0
        var failure: RouterApiFailure? = null
        try {
            prepareResolvedRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17), {
                resolverCalls++
                listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 2)))
            }, { ownerChecks++ })
        } catch (caught: RouterApiFailure) { failure = caught }
        assertEquals(RouterApiError.UNSUPPORTED, failure?.kind)
        assertEquals(0, resolverCalls)
        assertTrue(ownerChecks >= 1)
        assertEquals(original, profile.normalizedConfigJson)
    }

    @Test fun onePublicResolutionIsInjectedIntoTheExactRuntimePlanWithoutChangingTheProfile() {
        val profile = domainProfile()
        val original = profile.normalizedConfigJson
        val owner = RouterPhoneSession(profile.id, 3, 17)
        val before = Json.parseToJsonElement(VpnEngineConfig.prepare(profile).engineJson).jsonObject
        val plan = prepareRouterDialCandidate(profile, owner) { host -> assertEquals("vpn.example.com", host); listOf(InetAddress.getByName("1.1.1.2")) }
        val forced = Json.parseToJsonElement(plan.prepared.engineJson).jsonObject
        assertEquals(JsonPrimitive("1.1.1.2"), forced["outbound"]!!.jsonObject["server_ip"])
        assertEquals(before["outbound"]!!.jsonObject, JsonObject(forced["outbound"]!!.jsonObject - "server_ip"))
        assertEquals(before["dns"], forced["dns"]); assertEquals(before["traffic"], forced["traffic"])
        assertEquals(original, profile.normalizedConfigJson)
        assertEquals(listOf(RouterUpstreamTarget("1.1.1.2", 443, "tcp")), plan.targets)
        assertFalse(plan.toString().contains("vpn.example"))
    }

    @Test fun privateOrMixedDnsAnswersCannotBecomeADirectLease() {
        val profile = domainProfile()
        assertThrows(IllegalArgumentException::class.java) {
            prepareRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17)) {
                listOf(InetAddress.getByName("1.1.1.2"), InetAddress.getByName("127.0.0.1"))
            }
        }
    }

    @Test fun expiredLeaseOrChangedProfileCannotSupplyThePreparationCallback() {
        val profile = profile()
        val candidate = prepareRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17)) { error("literal does not resolve") }
        val plan = candidate.leased("a".repeat(32), 5000)
        assertTrue(routerDialPlanCurrent(plan, profile, 3, 4999))
        assertFalse(routerDialPlanCurrent(plan, profile, 3, 5000))
        assertFalse(routerDialPlanCurrent(plan, profile, 4, 4999))
        assertFalse(routerDialPlanCurrent(plan, profile.copy(normalizedConfigJson = profile.normalizedConfigJson + " "), 3, 4999))
        assertEquals(null, Json.parseToJsonElement(VpnEngineConfig.prepare(profile).engineJson).jsonObject["outbound"]!!.jsonObject["server_ip"])
    }

    @Test fun retryKeepsAClaimButANewCommandForTheSameProfileGetsAnotherClaim() {
        val profile = profile()
        val first = prepareRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17)) { error("literal") }
        val next = prepareRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 19)) { error("literal") }
        val seed = ByteArray(32) { 7 }
        assertEquals(routerLeaseClaim(seed, first), routerLeaseClaim(seed, first))
        assertFalse(routerLeaseClaim(seed, first) == routerLeaseClaim(seed, next))
    }

    @Test fun releaseRequiresTheExactCommandOwnerAndALiveManagerIntent() {
        val old = RouterPhoneSession("profile", 3, 17)
        val newer = RouterPhoneSession("profile", 3, 19)
        assertTrue(routerLeaseReleaseOwned(old, old, true))
        assertTrue(routerLeaseReleaseOwned(null, old, true))
        assertFalse(routerLeaseReleaseOwned(newer, old, true))
        assertFalse(routerLeaseReleaseOwned(old, old, false))
    }


    @Test fun aPausedDnsMemoIsBoundToTheCurrentWifiAndHasABoundedValidationLifetime() {
        val profile = profile()
        val owner = RouterPhoneSession(profile.id, 3, 17)
        val candidate = prepareRouterDialCandidate(profile, owner) { error("literal") }
        val memo = RouterValidatedDialMemo(candidate, 7, "192.168.1.50", 10000)
        assertTrue(memo.current(owner, 7, "192.168.1.50", 309999))
        assertFalse(memo.current(owner, 7, "192.168.1.50", 310000))
        assertFalse(memo.current(owner, 8, "192.168.1.50", 10001))
        assertFalse(memo.current(owner, 7, "192.168.1.51", 10001))
        assertFalse(memo.current(owner.copy(connectionId = 19), 7, "192.168.1.50", 10001))
    }


    @Test fun suspendProviderResolutionCannotDeliverAPlanAfterTheOwnerChanges() = runTest {
        val profile = domainProfile()
        val release = CompletableDeferred<Unit>()
        var owned = true
        var delivered = false
        var rejected = false
        val preparation = launch {
            try {
                prepareResolvedRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17), { host ->
                    assertEquals("vpn.example.com", host)
                    release.await()
                    listOf(InetAddress.getByName("1.1.1.2"))
                }, { require(owned) })
                delivered = true
            } catch (_: IllegalArgumentException) { rejected = true }
        }
        runCurrent()
        owned = false
        release.complete(Unit); runCurrent()
        assertTrue(preparation.isCompleted)
        assertFalse(delivered)
        assertTrue(rejected)
    }

    @Test fun cancelingSuspendedResolutionCannotDeliverAForcedRuntimeConfiguration() = runTest {
        val profile = domainProfile()
        val release = CompletableDeferred<Unit>()
        var delivered = false
        val preparation = launch {
            prepareResolvedRouterDialCandidate(profile, RouterPhoneSession(profile.id, 3, 17), {
                release.await(); listOf(InetAddress.getByName("1.1.1.2"))
            }, {})
            delivered = true
        }
        runCurrent()
        preparation.cancelAndJoin()
        release.complete(Unit); runCurrent()
        assertFalse(delivered)
        assertTrue(preparation.isCancelled)
    }

    @Test fun anAdmittedLiteralDoesNotInvokeTheProviderResolverAndDnsMemoHonorsItsRemainingTtl() = runTest {
        val profile = profile()
        val owner = RouterPhoneSession(profile.id, 3, 17)
        val candidate = prepareResolvedRouterDialCandidate(profile, owner, { error("literal must not invoke WAN resolution") }, {})
        assertEquals(listOf(RouterUpstreamTarget("1.1.1.1", 443, "tcp")), candidate.targets)
        val memo = RouterValidatedDialMemo(candidate, 7, "192.168.1.50", 10000, 500)
        assertTrue(memo.current(owner, 7, "192.168.1.50", 10499))
        assertFalse(memo.current(owner, 7, "192.168.1.50", 10500))
        assertFalse(RouterValidatedDialMemo(candidate, 7, "192.168.1.50", 10000, 0).current(owner, 7, "192.168.1.50", 10000))
    }

}
