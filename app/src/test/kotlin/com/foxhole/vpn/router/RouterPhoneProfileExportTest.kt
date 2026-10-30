package com.foxhole.vpn.router

import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RouterPhoneProfileExportTest {
    private val link = "vless://00000000-0000-0000-0000-000000000001@vpn.example.com:443?security=tls#Selected"
    private class Controller(profile: VpnProfileSummary, private val chosen: String, private val whole: String) : VpnController {
        override val state = MutableStateFlow(VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id, profileRevision = 4))
        override val settings = MutableStateFlow(VpnSettings())
        val selections = mutableListOf<Set<String>?>()
        var mutateDuringExport = false
        override suspend fun awaitReady() {}
        override suspend fun exportProfile(profileId: String, optionIds: Set<String>?): String {
            selections += optionIds
            if (mutateDuringExport && optionIds == null) state.value = state.value.copy(profileRevision = 5)
            return if (optionIds == null) whole else chosen
        }
        override fun connectSelected() = error("unrelated action")
        override fun disconnect() = error("unrelated action")
        override fun selectProfile(id: String) = error("unrelated action")
        override fun refreshLocation() = error("unrelated action")
        override suspend fun importProfiles(text: String, allowInsecureTls: Boolean): VpnImportResult = error("unrelated action")
        override suspend fun refreshSubscription(profileId: String): VpnImportResult = error("unrelated action")
        override suspend fun removeProfile(id: String) = error("unrelated action")
        override suspend fun updateSettings(settings: VpnSettings) = error("unrelated action")
    }

    private fun subscription() = VpnProfileSummary("phone-profile", "Home", "VLESS", true,
        listOf(VpnProtocolSummary("first", "First node", "HYSTERIA2"), VpnProtocolSummary("selected", "Selected node", "VLESS")), "selected")

    @Test fun multivariantSubscriptionPreservesTheSourceAndExactUserSelection() = runTest {
        val controller = Controller(subscription(), link, "https://subscription.example.com/private-token")
        val selected = RouterPhoneProfileExport(controller).selected("phone-profile")
        assertEquals("https://subscription.example.com/private-token", selected.sourceUrl)
        assertEquals("vless", selected.selectedProtocol)
        assertEquals("Selected node", selected.selectedNodeName)
        assertEquals("selected", selected.selectedOptionId)
        assertEquals(link, selected.selectedConfiguration)
        assertEquals(listOf(setOf("selected"), null), controller.selections)
        assertFalse(selected.toString().contains("private-token"))
        assertFalse(selected.toString().contains("00000000-0000"))
    }

    @Test fun aChangingStoredProfileCannotExportAnUnownedSelection() = runTest {
        val controller = Controller(subscription(), link, "https://subscription.example.com/private-token").apply { mutateDuringExport = true }
        try { RouterPhoneProfileExport(controller).selected("phone-profile"); error("changed profile exported") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.CONFLICT, failure.kind) }
    }

    @Test fun aDirectOutlineProfileExportsOnlyItsConfigWithoutInventingASource() = runTest {
        val profile = VpnProfileSummary("direct", "Outline", "OUTLINE")
        val controller = Controller(profile, "ss://synthetic@vpn.example.com:443#Outline", "ss://synthetic@vpn.example.com:443#Outline")
        val selected = RouterPhoneProfileExport(controller).selected("direct")
        assertEquals("shadowsocks", selected.selectedProtocol)
        assertEquals(listOf(emptySet<String>(), null), controller.selections)
        assertNull(selected.sourceUrl)
    }
}
