package com.foxhole.vpn.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VpnExpiryTest {
    private val importer = VpnProfileImporter()
    private val vless = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Fixture"

    @Test fun userinfoSecondsAndMillisecondsBecomeTheSameEpochMilliseconds() {
        assertEquals(1779563665000L, subscriptionExpiry("upload=1; expire=1779563665"))
        assertEquals(1779563665000L, subscriptionExpiry("expire=1779563665000"))
        assertEquals(
            java.time.Instant.parse("2028-01-01T00:00:00Z").toEpochMilli(),
            subscriptionExpiry("expire=2028-01-01T00:00:00Z")
        )
    }

    @Test fun absentMalformedAndUnboundedExpiryNeverInventADate() {
        listOf(
            null,
            "",
            "expire=0",
            "expire=-1779563665",
            "expire=unknown",
            "expire=${Long.MAX_VALUE}",
            "expire=1779563665;" + "x".repeat(1025)
        )
            .forEach { assertNull(subscriptionExpiry(it)) }
        assertEquals(253402300799999L, subscriptionExpiry("expire=253402300799999"))
        assertNull(subscriptionExpiry("expire=253402300800000"))
    }

    @Test fun directProfilePreservesParsedExpiryWithoutBlockingExpiredProfiles() {
        val profile = importer.import(vless.replace("#", "&expire=1000000000#")).profiles.single()
        assertEquals(1000000000000L, profile.subscriptionExpiresAt)
        assertEquals("VLESS", profile.protocol)
        assertNull(importer.import(vless).profiles.single().subscriptionExpiresAt)
    }

    @Test fun bodyMinimumPrecedesHttpMetadataAndHttpPrecedesOriginalUrlFallback() {
        val body = vless.replace("#", "&expire=1900000000#") + "\n" +
            "trojan://fixture@8.8.8.8:443?security=tls&sni=example.com&expire=1800000000#Fixture"
        val profile = importer.importSubscription(
            body,
            "https://1.1.1.1/sub?expire=1600000000",
            false,
            displayName = "Fixture plan",
            subscriptionExpiresAt = 1700000000000L
        ).profiles.single()
        assertEquals(1800000000000L, profile.subscriptionExpiresAt)
        assertEquals(
            1900000000000L,
            importer.importSubscription(
                vless,
                "https://1.1.1.1/sub?expire=1600000000",
                false,
                subscriptionExpiresAt = 1900000000000L
            ).profiles.single().subscriptionExpiresAt
        )
        assertEquals(
            1600000000000L,
            importer.importSubscription(
                vless,
                "https://1.1.1.1/sub?expire=1600000000",
                false
            ).profiles.single().subscriptionExpiresAt
        )
    }

    @Test fun oldVaultAndRedactedFetchResultStayBackwardCompatible() {
        val old = Json.encodeToString(VpnProfile.serializer(), importer.import(vless).profiles.single())
        assertFalse(old.contains("subscriptionExpiresAt"))
        assertNull(Json.decodeFromString(VpnProfile.serializer(), old).subscriptionExpiresAt)
        val response = SubscriptionFetchResult("secret-fixture", "Fixture", 1779563665000L)
        assertFalse(response.toString().contains("1779563665000"))
        assertFalse(response.toString().contains("secret-fixture"))
    }

    @Test fun expiryRefreshKeepsStableProfileAndSelectedProtocolIdentity() {
        val body = "$vless\ntrojan://fixture@8.8.8.8:443?security=tls&sni=example.com#Fixture"
        val first = importer.importSubscription(
            body,
            "https://1.1.1.1/sub",
            false,
            "First title",
            1779563665000L
        ).profiles.single()
        val refreshed = importer.importSubscription(
            body,
            "https://1.1.1.1/sub",
            false,
            "New title",
            1800000000000L
        ).profiles.single()
        assertEquals(first.id, refreshed.id)
        assertEquals(first.sourceIdentity, refreshed.sourceIdentity)
        val option = first.protocolOptions.last().id
        val chosen = refreshed.withSelectedProtocol(option)
        assertEquals(option, chosen.selectedProtocolOptionId)
        assertEquals(1800000000000L, chosen.subscriptionExpiresAt)
    }
}
