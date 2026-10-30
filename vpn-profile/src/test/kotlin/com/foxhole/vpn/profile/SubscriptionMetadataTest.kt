package com.foxhole.vpn.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class SubscriptionMetadataTest {
    @Test fun titleSupportsPlainAndBase64WithBoundedDisplayText() {
        val title = "Fixture VPN + Европа"
        assertEquals(title, subscriptionDisplayName(title))
        assertEquals(
            title,
            subscriptionDisplayName("base64:" + Base64.getEncoder().encodeToString(title.toByteArray(Charsets.UTF_8)))
        )
        assertNull(subscriptionDisplayName("base64:%%%"))
        assertNull(subscriptionDisplayName("x".repeat(8193)))
        assertEquals(160, subscriptionDisplayName("x".repeat(500))!!.length)
        assertEquals("FixtureVPN", subscriptionDisplayName("Fixture\nVPN"))
    }

    @Test fun providerTitleNamesGroupedSubscriptionWithoutLeakingBodyToString() {
        val body = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Node"
        val result = SubscriptionFetchResult(body, "Fixture plan")
        assertFalse(result.toString().contains("11111111"))
        val profile = VpnProfileImporter().importSubscription(
            body,
            "https://1.1.1.1/sub",
            false,
            result.displayName
        ).profiles.single()
        assertEquals("Fixture plan", profile.name)
    }

    @Test fun titleRefreshDoesNotChangeStableProfileOrProtocolChoiceIdentity() {
        val body = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Node\ntrojan://fixture@8.8.8.8:443?security=tls&sni=example.com#Node"
        val importer = VpnProfileImporter()
        val first = importer.importSubscription(body, "https://1.1.1.1/sub", false, "First title").profiles.single()
        val updated = importer.importSubscription(body, "https://1.1.1.1/sub", false, "New title").profiles.single()
        assertEquals(first.id, updated.id)
        assertEquals(first.sourceIdentity, updated.sourceIdentity)
        assertEquals(first.protocolOptions.map { it.id }, updated.protocolOptions.map { it.id })
        val chosen = first.protocolOptions.last().id
        assertEquals(chosen, updated.withSelectedProtocol(chosen).selectedProtocolOptionId)
        assertEquals("New title", updated.name)
    }
}
