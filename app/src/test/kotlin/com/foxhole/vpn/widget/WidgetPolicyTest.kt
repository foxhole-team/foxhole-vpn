package com.foxhole.vpn.widget

import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPolicyTest {
    private val profile = VpnProfileSummary("one", "Home", "VLESS")
    private val selected = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)

    @Test
    fun `first start requests vpn consent without connecting`() {
        assertEquals(
            WidgetDecision.REQUEST_PERMISSION,
            widgetDecision(WidgetAction.START, profile.id, selected, permissionGranted = false),
        )
        assertEquals(
            WidgetDecision.CONNECT,
            widgetDecision(WidgetAction.START, profile.id, selected, permissionGranted = true),
        )
    }

    @Test
    fun `missing or replaced profile cannot start another connection or open the app`() {
        assertNull(widgetAction(VpnUiState()))
        listOf(null, "another-profile").forEach { bound ->
            assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.START, bound, selected, true))
        }
        assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.START, profile.id, VpnUiState(isReady = true), true))
    }

    @Test
    fun `connecting and validating expose a working stop action`() {
        listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.CONNECTED).forEach { phase ->
            val state = selected.copy(phase = phase, hasOwnedTunnel = phase == VpnPhase.CONNECTED)
            assertEquals(WidgetAction.STOP, widgetAction(state))
            assertEquals(WidgetDecision.DISCONNECT, widgetDecision(WidgetAction.STOP, null, state, false))
            assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.START, profile.id, state, true))
        }
    }

    @Test
    fun `a stale stop never starts a disconnected vpn`() {
        listOf(VpnPhase.IDLE, VpnPhase.ERROR, VpnPhase.STOPPING).forEach { phase ->
            assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.STOP, null, selected.copy(phase = phase), true))
        }
        assertNull(widgetAction(selected.copy(phase = VpnPhase.STOPPING)))
    }

    @Test
    fun `an error that still owns the tunnel offers disconnect without showing connected`() {
        val state = VpnUiState(isReady = true, phase = VpnPhase.ERROR, hasOwnedTunnel = true)
        assertEquals(WidgetAction.STOP, widgetAction(state))
        assertEquals(WidgetDecision.DISCONNECT, widgetDecision(WidgetAction.STOP, null, state, false))
        assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.START, profile.id, state, true))
        assertFalse(state.isConnected)
        assertEquals(3, widgetFoxFrameIndex(state.phase))
    }

    @Test
    fun `unhydrated defaults cannot authorize a widget start`() {
        val loading = selected.copy(isReady = false)
        assertNull(widgetAction(loading))
        assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.START, profile.id, loading, true))
    }

    @Test
    fun `an owned tunnel remains stoppable when profile storage is unavailable`() {
        listOf(VpnPhase.CONNECTED, VpnPhase.ERROR, VpnPhase.STOPPING).forEach { phase ->
            val unavailable = VpnUiState(isReady = false, phase = phase, hasOwnedTunnel = true)
            assertEquals(WidgetAction.STOP, widgetAction(unavailable))
            assertEquals(WidgetDecision.DISCONNECT, widgetDecision(WidgetAction.STOP, null, unavailable, false))
            assertEquals(WidgetDecision.NONE, widgetDecision(WidgetAction.START, profile.id, unavailable, true))
        }
    }

    @Test
    fun `loading never conceals a vault error or real tunnel cleanup`() {
        val loading = VpnUiState(isReady = false)
        assertFalse(widgetShowsRuntimeStatus(loading))
        assertEquals(R.string.widget_loading, widgetStatusLabel(loading))
        for (owned in listOf(false, true)) {
            val failure = loading.copy(phase = VpnPhase.ERROR, hasOwnedTunnel = owned)
            assertTrue(widgetShowsRuntimeStatus(failure))
            assertEquals(R.string.widget_error, widgetStatusLabel(failure))
        }
        val stopping = loading.copy(phase = VpnPhase.STOPPING, hasOwnedTunnel = true)
        assertTrue(widgetShowsRuntimeStatus(stopping))
        assertEquals(R.string.widget_stopping, widgetStatusLabel(stopping))
    }

    @Test
    fun `fox remains disconnected until the backend validates connectivity`() {
        VpnPhase.entries.filter { it != VpnPhase.CONNECTED }.forEach { phase ->
            assertEquals(3, widgetFoxFrameIndex(phase))
        }
        assertEquals(0, widgetFoxFrameIndex(VpnPhase.CONNECTED))
    }

    @Test
    fun `system widget theme follows the system and explicit themes remain fixed`() {
        assertFalse(widgetUsesDarkTheme(VpnTheme.SYSTEM, false))
        assertTrue(widgetUsesDarkTheme(VpnTheme.SYSTEM, true))
        assertTrue(widgetUsesDarkTheme(VpnTheme.DARK, false))
        assertFalse(widgetUsesDarkTheme(VpnTheme.LIGHT, true))
    }
}
