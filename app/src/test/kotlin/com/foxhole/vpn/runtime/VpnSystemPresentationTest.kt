package com.foxhole.vpn.runtime

import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSystemPresentationTest {
    private val profile = VpnProfileSummary("one", "Home", "HYSTERIA2")
    private val idle = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)

    @Test fun validatedAndDisconnectedStatusesUseTheSystemLabels() {
        assertEquals(R.string.vpn_notification_disconnected, vpnSystemPresentation(idle).statusRes)
        assertEquals(R.string.vpn_notification_connected,
            vpnSystemPresentation(idle.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)).statusRes)
        assertEquals(R.string.vpn_status_validating, vpnSystemPresentation(idle.copy(phase = VpnPhase.VALIDATING)).statusRes)
    }
    @Test fun startsAlwaysUseAUserVisibleConsentActivity() {
        assertEquals(VpnTileClick.START_ACTIVITY, vpnTileClick(idle, true, true))
        assertEquals(VpnTileClick.OPEN_APP, vpnTileClick(idle, true, false))
        assertEquals(VpnTileClick.OPEN_APP, vpnTileClick(idle, false, false))
        assertEquals(VpnTileClick.OPEN_APP, vpnTileClick(idle.copy(profiles = emptyList()), true, true))
    }
    @Test fun loadingVaultCannotStartOrQueueAConnection() {
        assertEquals(VpnTileClick.NONE, vpnTileClick(VpnUiState(), true, true))
        assertEquals(VpnSystemAction.NONE, vpnSystemPresentation(VpnUiState()).action)
    }
    @Test fun activeAndFailClosedOwnedTunnelsKeepAStopWithoutLicenseOrStorage() {
        for (phase in listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.CONNECTED, VpnPhase.ERROR)) {
            val owned = VpnUiState(phase = phase, hasOwnedTunnel = true)
            assertEquals(VpnTileClick.STOP, vpnTileClick(owned, false, false))
            assertTrue(vpnSystemPresentation(owned).active)
        }
    }
    @Test fun stoppingHasNoSecondStopOrStartAction() {
        val stopping = idle.copy(phase = VpnPhase.STOPPING, hasOwnedTunnel = true, canRestartDuringStop = true)
        assertEquals(VpnTileClick.NONE, vpnTileClick(stopping, true, true))
        assertEquals(VpnSystemAction.NONE, vpnSystemPresentation(stopping).action)
    }

    @Test fun tileRebindRejectsTheOldUnlockCallbackAndConsumesTheNewOneOnce() {
        val gate = VpnTileUnlockGate()
        val old = requireNotNull(gate.claim())
        org.junit.Assert.assertNull(gate.claim())
        gate.newListeningSession()
        val current = requireNotNull(gate.claim())
        org.junit.Assert.assertFalse(gate.consume(old))
        assertTrue(gate.pending)
        assertTrue(gate.consume(current))
        org.junit.Assert.assertFalse(gate.consume(current))
        org.junit.Assert.assertFalse(gate.pending)
    }
}
