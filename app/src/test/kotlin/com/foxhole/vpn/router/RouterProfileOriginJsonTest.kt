package com.foxhole.vpn.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouterProfileOriginJsonTest {
    private val pairId = "pair-" + "a".repeat(32)
    private val uri = "hysteria2://SyntheticOnly@router.example:443/#Synthetic"
    private val identity = RouterTlsIdentity(RouterEndpoint("10.23.45.1"), "b".repeat(64))
    private fun metadata() = buildJsonObject {
        put("schema", 1); put("kind", "foxhole-openwrt-client"); put("pairingId", pairId)
        put("clientId", "client-" + "c".repeat(12)); put("uriSha256", routerProfileDigest(uri))
    }
    private fun parsed(value: JsonElement = metadata()) = requireNotNull(routerProfileMarker(value, uri, pairId))
    private fun vault(active: Boolean = true): RouterSavedVault = RouterSavedVault(selectedId = pairId,
        routers = listOf(RouterSavedEntry(RouterPairing(identity, pairId, ByteArray(32) { 7 }, "Synthetic phone", active), 1)))
    private fun origin(profileId: String = "local-profile") = RouterTrustedProfile(profileId, "d".repeat(64), identity.certificateSha256, parsed())

    @Test fun exactBackendDtoAndAbsentMarkerKeepTheirDifferentMeanings() {
        assertEquals(parsed(), routerProfileMarker(Json.parseToJsonElement(metadata().toString()), uri, pairId))
        assertEquals(null, routerProfileMarker(null, uri, pairId))
    }
    @Test fun markerFieldTypesAreStrict() {
        reject { parsed(JsonObject(metadata() + ("schema" to JsonPrimitive("1")))) }
        reject { parsed(JsonObject(metadata() + ("uriSha256" to JsonPrimitive(42)))) }
        reject { parsed(JsonNull) }
    }
    @Test fun missingOrUnknownFieldsCannotIntroduceTargetsOrAuthority() {
        reject { parsed(JsonObject(metadata() - "clientId")) }
        reject { parsed(JsonObject(metadata() + ("url" to JsonPrimitive("https://provider.example/")))) }
        reject { parsed(JsonObject(metadata() + ("trusted" to JsonPrimitive(true)))) }
    }
    @Test fun oversizedOrForeignMetadataIsRejected() {
        reject { parsed(JsonObject(metadata() + ("kind" to JsonPrimitive("x".repeat(300))))) }
        reject { parsed(JsonObject(metadata() + ("pairingId" to JsonPrimitive("pair-" + "f".repeat(32))))) }
        reject { parsed(JsonObject(metadata() + ("schema" to JsonPrimitive(2)))) }
    }
    @Test fun exactUriHashIsCheckedBeforeNameAdaptation() {
        assertTrue(parsed().matches(uri, pairId))
        reject { routerProfileMarker(metadata(), uri + "renamed", pairId) }
    }
    @Test fun decodedNulAndDelAreRejectedEvenWithTheirExactHash() {
        for (control in listOf(0, 31, 127)) {
            val bad = uri + control.toChar() + "tail"
            val value = Json.parseToJsonElement(JsonObject(metadata() + ("uriSha256" to JsonPrimitive(routerProfileDigest(bad)))).toString())
            reject { routerProfileMarker(value, bad, pairId) }
        }
    }
    @Test fun trustedOriginCommitPreservesTheOldVaultUntilItSucceeds() {
        val old = vault()
        val next = old.withTrustedProfile(identity, origin())
        assertTrue(old.routers.single().trustedProfiles.isEmpty())
        assertEquals(listOf("local-profile"), next.routers.single().trustedProfiles.map { it.profileId })
        assertEquals(old.inventory(), next.inventory())
    }
    @Test fun selectedPairAndExpectedCertificateAreCheckedAtCommit() {
        val old = vault()
        reject { old.withTrustedProfile(identity.copy(certificateSha256 = "e".repeat(64)), origin()) }
        reject { old.copy(selectedId = null).withTrustedProfile(identity, origin()) }
        assertTrue(old.routers.single().trustedProfiles.isEmpty())
        assertEquals(pairId, old.selectedId)
    }
    @Test fun anUnvalidatedPairCannotAcquireTrustedProfileMetadata() {
        val old = vault(active = false)
        reject { old.withTrustedProfile(identity, origin()) }
        assertTrue(old.routers.single().trustedProfiles.isEmpty())
    }
    @Test fun boundedOriginHistoryDoesNotRemovePairingOrOtherRouterData() {
        var value = vault()
        for (index in 0..32) value = value.withTrustedProfile(identity, origin("profile-$index"))
        assertEquals(32, value.routers.single().trustedProfiles.size)
        assertEquals("profile-1", value.routers.single().trustedProfiles.first().profileId)
        assertEquals(pairId, value.routers.single().pairing.id)
        value.validate()
    }
    private fun reject(action: () -> Unit) {
        try { action(); fail("expected rejection") }
        catch (failure: Exception) {
            assertTrue(failure is RouterApiFailure || failure is RouterIdentityChanged || failure is IllegalArgumentException)
        }
    }
}
