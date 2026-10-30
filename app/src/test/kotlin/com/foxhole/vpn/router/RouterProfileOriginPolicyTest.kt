package com.foxhole.vpn.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterProfileOriginPolicyTest {
    private val pair = "pair-" + "a".repeat(32)
    private val client = "client-" + "b".repeat(12)
    private val uri = "hysteria2://SyntheticOnly@router.example:443/?sni=router.example#Synthetic"
    private fun valid(schema: Int = 1, kind: String = "foxhole-openwrt-client", pairing: String = pair,
        clientId: String = client, digest: String = routerProfileDigest(uri), value: String = uri,
        expected: String = pair) = validRouterProfileMarker(schema, kind, pairing, clientId, digest, value, expected)

    @Test fun currentExactAuthenticatedExportHintHasValidSyntaxAndUriBinding() { assertTrue(valid()) }
    @Test fun unsupportedVersionAndForeignKindAreRejected() {
        assertFalse(valid(schema = 0)); assertFalse(valid(schema = 2)); assertFalse(valid(kind = "provider"))
    }
    @Test fun foreignPairingCannotReuseAValidUriDigest() { assertFalse(valid(expected = "pair-" + "c".repeat(32))) }
    @Test fun malformedIdsAndDigestAreRejected() {
        assertFalse(valid(pairing = "pair-a")); assertFalse(valid(clientId = "client-anything"))
        assertFalse(valid(digest = "f".repeat(63))); assertFalse(valid(digest = "A".repeat(64)))
    }
    @Test fun uriTamperingOrNameChangesInvalidateTheExactExportHash() {
        assertFalse(valid(value = uri.replace("443", "444")))
        assertFalse(valid(value = uri + "changed"))
    }
    @Test fun controlCharactersAndNonVpnUrlsAreRejected() {
        for (control in listOf(0, 1, 9, 10, 31, 127, 159)) {
            val bad = uri + control.toChar() + "tail"
            assertFalse(valid(value = bad, digest = routerProfileDigest(bad)))
        }
        val url = "https://router.example/"
        assertFalse(valid(value = url, digest = routerProfileDigest(url)))
    }
    @Test fun sizeBoundCountsUtf8Bytes() {
        val bad = "hysteria2://" + "ю".repeat(9000)
        assertFalse(valid(value = bad, digest = routerProfileDigest(bad)))
    }
    @Test fun parsedProviderHintDoesNotAuthorizeAClonedProfileId() {
        assertTrue(valid())
        val digest = routerProfileDigest("normalized synthetic config")
        assertFalse(routerLocalProfileMatches("cloned-profile", digest, "authenticated-local-profile", digest))
        assertTrue(routerLocalProfileMatches("authenticated-local-profile", digest, "authenticated-local-profile", digest))
    }
    @Test fun editingTheNormalizedConfigInvalidatesTheLocalOrigin() {
        assertFalse(routerLocalProfileMatches("local-profile", routerProfileDigest("edited"), "local-profile", routerProfileDigest("original")))
    }
    @Test fun aConnectionGenerationGetsOneAttemptIncludingAfterFailure() {
        val gate = RouterProfileProbeGate()
        val key = RouterProfileProbeKey("local-profile", "a".repeat(64), pair, "connection-1")
        assertTrue(gate.claim(key)); assertFalse(gate.claim(key)); assertFalse(gate.claim(key.copy()))
    }
    @Test fun newConnectionAndNewPairingHaveIndependentAttempts() {
        val gate = RouterProfileProbeGate()
        val key = RouterProfileProbeKey("local-profile", "a".repeat(64), pair, "connection-1")
        assertTrue(gate.claim(key))
        assertTrue(gate.claim(key.copy(connectionGeneration = "connection-2")))
        assertTrue(gate.claim(key.copy(pairingId = "pair-" + "c".repeat(32), connectionGeneration = "connection-2")))
    }
}
