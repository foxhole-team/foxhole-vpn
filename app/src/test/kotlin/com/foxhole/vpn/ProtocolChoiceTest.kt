package com.foxhole.vpn

import com.foxhole.vpn.engine.EngineFailure
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolChoiceTest {
    @Test fun aDifferentActiveOptionRequiresAnOwnerBoundConfirmation() {
        val state = active()
        assertEquals(ProtocolChoiceAction.CONFIRM, protocolChoiceAction(state, "p", "b"))
        val request = requireNotNull(protocolChangeRequest(state, "p", "b"))
        assertEquals(VpnProtocolChangeRequest("p", "b", "a", 17, 4), request)
        assertTrue(protocolChangeOwnerCurrent(request, state, owned(), 17, false))
    }

    @Test fun retainedErrorAndRevalidationStillCountAsOwnedConnections() {
        for (phase in listOf(VpnPhase.ERROR, VpnPhase.VALIDATING)) {
            val state = active().copy(phase = phase)
            val request = requireNotNull(protocolChangeRequest(state, "p", "b"))
            assertTrue(protocolChangeOwnerCurrent(request, state, owned().copy(phase = EnginePhase.valueOf(phase.name)), 17, false))
        }
    }

    @Test fun disconnectedChoiceIsImmediateAndHasNoReconnectConfirmation() {
        val state = active().copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null)
        assertEquals(ProtocolChoiceAction.IMMEDIATE, protocolChoiceAction(state, "p", "b"))
        assertNull(protocolChangeRequest(state, "p", "b"))
    }

    @Test fun sameMissingAndUnknownChoicesNeverRequestWork() {
        for (state in listOf(active(), active().copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null))) {
            assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(state, "p", "a"))
            assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(state, "missing", "b"))
            assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(state, "p", "unknown"))
            assertNull(protocolChangeRequest(state, "p", "a"))
        }
    }

    @Test fun anotherProfileCannotReplaceTheActiveProfileThroughItsProtocolRow() {
        val state = active().copy(profiles = listOf(profile, profile.copy(id = "other")))
        assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(state, "other", "b"))
    }

    @Test fun startStopAndMissingOwnershipRefuseProtocolSwitching() {
        for (phase in listOf(VpnPhase.CONNECTING, VpnPhase.STOPPING)) {
            assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(active().copy(phase = phase), "p", "b"))
        }
        assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(active().copy(hasOwnedTunnel = false), "p", "b"))
        assertEquals(ProtocolChoiceAction.NONE, protocolChoiceAction(active().copy(isReady = false), "p", "b"))
    }

    @Test fun staleRevisionOptionProfileOrConnectionCannotUseTheOldConfirmation() {
        val state = active()
        val request = requireNotNull(protocolChangeRequest(state, "p", "b"))
        val changed = listOf(
            state.copy(profileRevision = 5), state.copy(connectionId = 18), state.copy(profiles = emptyList()),
            state.copy(profiles = listOf(profile.copy(selectedProtocolOptionId = "b"))),
            state.copy(profiles = listOf(profile.copy(protocolOptions = profile.protocolOptions.take(1)))),
            state.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null),
        )
        changed.forEach { assertFalse(protocolChangeCurrent(request, it)) }
    }

    @Test fun pendingStartAndAnOldActorTicketCannotMasqueradeAsTheConfirmedOwner() {
        val state = active()
        val request = requireNotNull(protocolChangeRequest(state, "p", "b"))
        assertFalse(protocolChangeOwnerCurrent(request, state, owned(), 18, false))
        assertFalse(protocolChangeOwnerCurrent(request, state, owned(), 17, true))
        assertFalse(protocolChangeOwnerCurrent(request, state, owned().copy(commandTicket = 16), 17, false))
        assertFalse(protocolChangeOwnerCurrent(request, state, owned().copy(profileId = "other"), 17, false))
        assertFalse(protocolChangeOwnerCurrent(request, state, owned().copy(hasOwnedTunnel = false), 17, false))
    }

    @Test fun failedCleanupKeepsStopAvailableAndCannotAdmitANewProtocol() {
        val state = active().copy(phase = VpnPhase.ERROR)
        val request = requireNotNull(protocolChangeRequest(state, "p", "b"))
        assertTrue(state.canDisconnect)
        assertFalse(protocolChangeOwnerCurrent(request, state, owned().copy(phase = EnginePhase.ERROR, failure = EngineFailure.STOP), 17, false))
        assertTrue(protocolChangeOwnerCurrent(request, state, owned().copy(phase = EnginePhase.ERROR, failure = EngineFailure.VALIDATION), 17, false))
        assertTrue(profileRemovalCurrent(state.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null), "p", 4))
        assertFalse(profileRemovalCurrent(state, "p", 4))
    }

    private fun active() = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = "p",
        phase = VpnPhase.CONNECTED, hasOwnedTunnel = true, connectionId = 17, profileRevision = 4)

    private fun owned() = EngineSnapshot(phase = EnginePhase.CONNECTED, profileId = "p", hasOwnedTunnel = true, commandTicket = 17)

    private val profile = VpnProfileSummary("p", "Owned profile", "VLESS", protocolOptions = listOf(
        VpnProtocolSummary("a", "Owned first", "VLESS"), VpnProtocolSummary("b", "Owned second", "HYSTERIA2")), selectedProtocolOptionId = "a")
}
