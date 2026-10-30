package com.foxhole.vpn.ui

import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPresentationTest {
    @Test
    fun startNeedsReadyStorageAndASelectedProfile() {
        val profile = VpnProfileSummary("owned", "Owned profile", "vless")
        for (state in listOf(
            VpnUiState(),
            VpnUiState(isReady = true),
            VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = "missing"),
            VpnUiState(profiles = listOf(profile), selectedProfileId = profile.id),
        )) {
            assertEquals(VpnPrimaryAction.CONNECT, primaryAction(state))
            assertEquals(R.string.vpn_start, primaryLabel(state))
            assertFalse(primaryActionEnabled(state))
        }
    }

    @Test
    fun startInFlightRemainsCancelableAndCannotStartAnotherTunnel() {
        for (phase in listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.CONNECTED, VpnPhase.STOPPING)) {
            val state = VpnUiState(phase = phase)
            assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(state))
            assertEquals(R.string.vpn_stop, primaryLabel(state))
            assertTrue(primaryActionEnabled(state))
        }
        assertEquals(
            VpnPrimaryAction.DISCONNECT,
            primaryAction(VpnUiState(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)),
        )
    }

    @Test fun explicitStopOffersAQueuedStartAndItsCancellationWithoutHidingCleanupErrors() {
        val profile = VpnProfileSummary("owned", "Owned profile", "VLESS")
        val stopping = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id,
            phase = VpnPhase.STOPPING, hasOwnedTunnel = true, canRestartDuringStop = true)
        assertEquals(VpnPrimaryAction.CONNECT, primaryAction(stopping))
        assertTrue(primaryActionEnabled(stopping))
        assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(stopping.copy(hasPendingStart = true)))
        assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(stopping.copy(message = "cleanup_failed")))
        assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(stopping.copy(canRestartDuringStop = false)))
        assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(stopping.copy(phase = VpnPhase.ERROR)))
    }

    @Test
    fun errorWithOwnedTunnelAndNoProfileKeepsStopEnabledWithoutOldLocation() {
        val held = VpnUiState(
            isReady = false,
            phase = VpnPhase.ERROR,
            hasOwnedTunnel = true,
            location = VpnLocation("NL", "Previous city", "Previous country", "192.0.2.8"),
        )
        assertNull(held.selectedProfile)
        assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(held))
        assertEquals(R.string.vpn_stop, primaryLabel(held))
        assertTrue(primaryActionEnabled(held))
        assertNull(validatedExitLocation(held))
    }

    @Test
    fun errorWithoutOwnedTunnelAndNoProfileKeepsStartDisabled() {
        val released = VpnUiState(isReady = true, phase = VpnPhase.ERROR)
        assertEquals(VpnPrimaryAction.CONNECT, primaryAction(released))
        assertEquals(R.string.vpn_start, primaryLabel(released))
        assertFalse(primaryActionEnabled(released))
        assertFalse(released.canDisconnect)
    }

    @Test
    fun errorWithoutOwnedTunnelCanRetryTheSelectedProfile() {
        val released = VpnUiState(
            isReady = true,
            profiles = listOf(VpnProfileSummary("owned", "Owned profile", "vless")),
            selectedProfileId = "owned",
            phase = VpnPhase.ERROR,
        )
        assertEquals(VpnPrimaryAction.CONNECT, primaryAction(released))
        assertEquals(R.string.vpn_start, primaryLabel(released))
        assertTrue(primaryActionEnabled(released))
    }

    @Test
    fun previousLocationDoesNotBecomeTheExitDuringAnotherStartOrFailure() {
        val location = VpnLocation("NL", "Owned city", "Owned country", "192.0.2.7")
        for (phase in listOf(VpnPhase.IDLE, VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.ERROR)) {
            assertNull(validatedExitLocation(VpnUiState(phase = phase, location = location)))
        }
        assertEquals(location, validatedExitLocation(VpnUiState(phase = VpnPhase.CONNECTED, location = location)))
        assertNull(validatedExitLocation(VpnUiState(phase = VpnPhase.STOPPING, location = location)))
    }

    @Test
    fun countryCodeCannotEscapeTheBundledFlagDirectory() {
        assertEquals("nl", countryAssetCode("NL"))
        assertEquals("us", countryAssetCode("us"))
        for (code in listOf("", "../secret", "/etc/passwd", "a1", "nld", "ＮＬ", "éé")) {
            assertNull(countryAssetCode(code))
        }
    }
}
