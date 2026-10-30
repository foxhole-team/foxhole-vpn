package com.foxhole.vpn

import com.foxhole.vpn.engine.EngineFailure
import com.foxhole.vpn.engine.EngineLocation
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot
import com.foxhole.vpn.ui.VpnPrimaryAction
import com.foxhole.vpn.ui.primaryAction
import com.foxhole.vpn.ui.primaryActionEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeUiProjectionTest {
    private val location = EngineLocation("US", "Owned city", "Owned country", "192.0.2.1")

    @Test
    fun aMatchingAcceptedOwnerAloneCanShowConnectedGeography() {
        val state = project(EngineSnapshot(EnginePhase.CONNECTED, location = location, hasOwnedTunnel = true, commandTicket = 4L))
        assertTrue(state.isConnected)
        assertEquals(4L, state.connectionId)
        assertEquals("Owned city", state.location?.city)
    }

    @Test
    fun oldConnectedSnapshotsNeverPaintTheReplacementAsConnected() {
        val state = project(EngineSnapshot(EnginePhase.CONNECTED, location = location, hasOwnedTunnel = true, commandTicket = 3L))
        assertEquals(VpnPhase.STOPPING, state.phase)
        assertFalse(state.isConnected)
        assertTrue(state.canDisconnect)
        assertNull(state.location)
        assertEquals(3L, state.connectionId)
    }

    @Test
    fun anUnacknowledgedStartKeepsStopAvailableWithoutGuessingItsIdentity() {
        val state = project(EngineSnapshot(), pending = true)
        assertEquals(VpnPhase.CONNECTING, state.phase)
        assertTrue(state.canDisconnect)
        assertNull(state.connectionId)
        assertNull(state.location)
    }

    @Test
    fun aFailedReplacementKeepsItsFixedErrorThroughReleasedCleanup() {
        val failure = PendingRuntimeUiFailure(4L, 1)
        val stopping = project(
            EngineSnapshot(EnginePhase.CONNECTED, hasOwnedTunnel = true, commandTicket = 3L),
            failure = failure,
        )
        val released = project(EngineSnapshot(), failure = failure)
        assertEquals(VpnPhase.STOPPING, stopping.phase)
        assertTrue(stopping.canDisconnect)
        assertEquals("fixed-1", stopping.message)
        assertEquals(VpnPhase.ERROR, released.phase)
        assertFalse(released.canDisconnect)
        assertEquals("fixed-1", released.message)
        val releasedError = project(EngineSnapshot(EnginePhase.ERROR, failure = EngineFailure.START, commandTicket = 3L), failure = failure)
        assertEquals(VpnPhase.ERROR, releasedError.phase)
        assertFalse(releasedError.canDisconnect)
        assertEquals("fixed-1", releasedError.message)
    }

    @Test
    fun aNewOwnerOrExplicitStopRevokesTheOldTerminalUiFailure() {
        val state = project(EngineSnapshot(), failure = PendingRuntimeUiFailure(3L, 1))
        assertEquals(VpnPhase.IDLE, state.phase)
        assertNull(state.message)
    }

    @Test
    fun aRealStopFailureAlwaysRemainsVisibleAndActionable() {
        val state = project(
            EngineSnapshot(EnginePhase.ERROR, failure = EngineFailure.STOP, hasOwnedTunnel = true, commandTicket = 3L),
            pending = true, failure = PendingRuntimeUiFailure(4L, 1), fault = "storage-fault",
        )
        assertEquals(VpnPhase.ERROR, state.phase)
        assertEquals("runtime-STOP", state.message)
        assertTrue(state.canDisconnect)
        assertNull(state.location)
    }

    @Test
    fun aStoreFaultDoesNotFreezeTheActualTunnelCleanupState() {
        val stopping = project(EngineSnapshot(EnginePhase.STOPPING, hasOwnedTunnel = true, commandTicket = 4L), fault = "storage-fault")
        val released = project(EngineSnapshot(), fault = "storage-fault")
        assertEquals(VpnPhase.STOPPING, stopping.phase)
        assertTrue(stopping.canDisconnect)
        assertFalse(released.canDisconnect)
        assertEquals(VpnPhase.ERROR, released.phase)
        assertEquals("storage-fault", released.message)
        assertNull(released.connectionId)
        val opening = project(EngineSnapshot(EnginePhase.CONNECTING, commandTicket = 4L), fault = "storage-fault")
        assertEquals(VpnPhase.STOPPING, opening.phase)
        assertTrue(opening.canDisconnect)
    }

    @Test
    fun aStoreFaultDisablesIdleStartWhileKeepingStopForTheOwnedTunnel() {
        val profile = VpnProfileSummary("p", "Owned profile", "VLESS")
        val current = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)
        val idle = project(EngineSnapshot(), fault = "storage-fault", current = current)
        assertFalse(idle.isReady)
        assertEquals(VpnPrimaryAction.CONNECT, primaryAction(idle))
        assertFalse(primaryActionEnabled(idle))
        val retained = project(
            EngineSnapshot(EnginePhase.CONNECTED, hasOwnedTunnel = true, commandTicket = 4L),
            fault = "storage-fault", current = current,
        )
        assertFalse(retained.isReady)
        assertEquals(VpnPrimaryAction.DISCONNECT, primaryAction(retained))
        assertTrue(primaryActionEnabled(retained))
        assertFalse(retained.isConnected)
    }

    @Test
    fun runtimeProjectionNeverPromotesUnfinishedStorageReadiness() {
        val profile = VpnProfileSummary("p", "Owned profile", "VLESS")
        val loading = VpnUiState(isReady = false, profiles = listOf(profile), selectedProfileId = profile.id)
        val idle = project(EngineSnapshot(), current = loading)
        assertFalse(idle.isReady)
        assertFalse(primaryActionEnabled(idle))
        val owned = project(
            EngineSnapshot(EnginePhase.CONNECTED, hasOwnedTunnel = true, commandTicket = 4L), current = loading,
        )
        assertFalse(owned.isReady)
        assertTrue(primaryActionEnabled(owned))
    }

    private fun project(
        snapshot: EngineSnapshot,
        pending: Boolean = false,
        failure: PendingRuntimeUiFailure? = null,
        fault: String? = null,
        current: VpnUiState = VpnUiState(isReady = true),
    ) = projectVpnRuntime(current, snapshot, 4L, pending, failure, fault, { "runtime-${it.name}" }, { "fixed-$it" })
}
