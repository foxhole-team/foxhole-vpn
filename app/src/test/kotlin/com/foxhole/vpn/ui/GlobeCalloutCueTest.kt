package com.foxhole.vpn.ui

import com.foxhole.vpn.VpnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeCalloutCueTest {
    @Test fun connectingAndValidationHideHomeUntilARealReturnToIdle() {
        val home = GlobeCalloutCue().locationReady(false, true)
        for (phase in listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.STOPPING)) {
            assertFalse(home.phaseChanged(phase, false, true).deviceExpanded)
        }
        val server = home.connectionChanged(true, 17L).dismissServer()
        val idle = server.phaseChanged(VpnPhase.IDLE, true, true)
        assertEquals(GlobeCallout.DEVICE, idle.connectionChanged(false, null).visible(false))
        assertEquals(GlobeCallout.SERVER, idle.connectionChanged(true, 18L).visible(true))
        val error = server.phaseChanged(VpnPhase.ERROR, false, true)
        assertEquals(GlobeCallout.NONE, error.connectionChanged(false, 17L).visible(false))
    }

    @Test fun disconnectedWithoutHomeCoordinatesWaitsForARealHomeLocation() {
        val idle = GlobeCalloutCue().connectionChanged(true, 17L).phaseChanged(VpnPhase.IDLE, true, false)
        assertTrue(idle.introPending)
        assertEquals(GlobeCallout.NONE, idle.visible(false))
        assertEquals(GlobeCallout.DEVICE, idle.locationReady(false, true).visible(false))
        assertEquals(GlobeCallout.NONE, idle.phaseChanged(VpnPhase.CONNECTING, false, false).locationReady(false, true).visible(false))
    }

    @Test fun lateDeviceLocationOpensOnceAndInteractionDoesNotQueueAnotherIntro() {
        val pending = GlobeCalloutCue().locationReady(confirmed = false, hasDevice = false)
        assertTrue(pending.introPending)
        val shown = pending.locationReady(confirmed = false, hasDevice = true)
        assertEquals(GlobeCallout.DEVICE, shown.visible(confirmed = false))
        val dismissed = shown.dismissDevice()
        assertEquals(dismissed, dismissed.locationReady(confirmed = false, hasDevice = true))
        assertEquals(GlobeCallout.NONE, dismissed.visible(confirmed = false))
        assertFalse(GlobeCalloutCue().touchStarted().locationReady(false, true).deviceExpanded)
    }

    @Test fun connectedDeviceSelectionIsExclusiveAndSurvivesSameConnectionHealthRefresh() {
        val server = GlobeCalloutCue().connectionChanged(true, 17L)
        val device = server.showDevice(true)
        assertEquals(GlobeCallout.DEVICE, device.visible(true))
        assertTrue(device.serverDismissed)
        assertEquals(GlobeCallout.DEVICE, device.locationReady(true, true).visible(true))
        assertEquals(GlobeCallout.DEVICE, device.connectionChanged(true, 17L).visible(true))
        assertEquals(GlobeCallout.NONE, device.visible(false))
        assertEquals(GlobeCallout.NONE, device.connectionChanged(false, 17L).connectionChanged(true, 17L).visible(true))
        assertEquals(GlobeCallout.NONE, device.toggleDevice(true).visible(true))
        assertEquals(GlobeCallout.SERVER, device.showServer(true).visible(true))
        assertEquals(GlobeCallout.SERVER, device.connectionChanged(true, 18L).visible(true))
    }

    @Test fun lostConfirmationHidesTheServerWithoutReopeningTheDeviceIntro() {
        val confirmed = GlobeCalloutCue().locationReady(confirmed = true, hasDevice = true)
        val lost = confirmed.locationReady(confirmed = false, hasDevice = true)
        assertEquals(GlobeCallout.NONE, lost.visible(confirmed = false))
        assertEquals(GlobeCallout.SERVER, lost.visible(confirmed = true))
    }

    @Test fun sameProcessRestorationPreservesDismissalAndExplicitDeviceSelection() {
        val dismissed = GlobeCalloutCue.restore(true, introPending = false, deviceExpanded = false)
        assertEquals(GlobeCallout.NONE, dismissed.locationReady(false, true).visible(false))
        val selected = GlobeCalloutCue.restore(true, introPending = false, deviceExpanded = true)
        assertEquals(GlobeCallout.DEVICE, selected.visible(false))
    }

    @Test fun newColdProcessDiscardsSavedDismissalAndWaitsForCoordinatesAgain() {
        val cold = GlobeCalloutCue.restore(false, introPending = false, deviceExpanded = false)
        assertTrue(cold.introPending)
        assertEquals(GlobeCallout.NONE, cold.locationReady(false, false).visible(false))
        assertEquals(GlobeCallout.DEVICE, cold.locationReady(false, true).visible(false))
        assertEquals(GlobeCallout.SERVER, cold.locationReady(true, true).visible(true))
    }

    @Test fun legacyOrMalformedSavedValuesDoNotBreakRestorationAfterAnUpgrade() {
        for (saved in listOf(null, 0, 1, "legacy", emptyList<Any>(), listOf("older-process", false, true), listOf("current-process", false, false))) {
            assertEquals(GlobeCalloutCue(), GlobeCalloutCue.restore(saved, "current-process"))
        }
        val cue = GlobeCalloutCue(false, false)
        assertEquals(cue, GlobeCalloutCue.restore(cue.save("current-process"), "current-process"))
    }

    @Test fun explicitServerDismissalSurvivesSameConnectionInteractionAndHealthRefresh() {
        val shown = GlobeCalloutCue().connectionChanged(true, 17L)
        val hidden = shown.dismissServer()
        assertEquals(GlobeCallout.NONE, hidden.visible(true))
        for (cue in listOf(hidden.touchStarted(), hidden.dismissDevice(),
            hidden.locationReady(true, true), hidden.connectionChanged(true, 17L), hidden.connectionChanged(false, 17L).connectionChanged(true, 17L))) {
            assertEquals(GlobeCallout.NONE, cue.visible(true))
            assertEquals(GlobeCallout.NONE, cue.visible(false))
        }
        assertEquals(GlobeCallout.SERVER, hidden.showServer(true).visible(true))
        assertEquals(hidden, hidden.showServer(false))
        assertEquals(GlobeCallout.SERVER, hidden.connectionChanged(true, 18L).visible(true))
    }

    @Test fun manualRecenteringHidesEitherConnectedCardUntilExplicitReopen() {
        val server = GlobeCalloutCue().connectionChanged(true, 17L)
        for (cue in listOf(server, server.showDevice(true))) {
            val centered = cue.recenter(true)
            assertEquals(GlobeCallout.NONE, centered.visible(true))
            assertEquals(GlobeCallout.NONE, centered.connectionChanged(true, 17L).visible(true))
            assertEquals(GlobeCallout.SERVER, centered.showServer(true).visible(true))
            assertEquals(GlobeCallout.SERVER, centered.connectionChanged(true, 18L).visible(true))
        }
    }

    @Test fun nullableConnectionIdsReopenOnlyAfterANewValidationEdge() {
        val hidden = GlobeCalloutCue().connectionChanged(true, null).dismissServer()
        assertEquals(GlobeCallout.NONE, hidden.connectionChanged(true, null).visible(true))
        val lost = hidden.connectionChanged(false, null)
        assertEquals(GlobeCallout.NONE, lost.visible(false))
        assertEquals(GlobeCallout.SERVER, lost.connectionChanged(true, null).visible(true))
    }

    @Test fun versionedSameProcessRestorationKeepsServerDismissalAndConnectionIdentity() {
        val hidden = GlobeCalloutCue().connectionChanged(true, 17L).dismissServer()
        val saved = hidden.save("owned-process")
        val restored = GlobeCalloutCue.restore(saved, "owned-process")
        assertEquals(hidden, restored)
        assertEquals(GlobeCallout.NONE, restored.connectionChanged(true, 17L).visible(true))
        assertEquals(GlobeCallout.SERVER, restored.connectionChanged(true, 18L).visible(true))
        assertEquals(GlobeCalloutCue(), GlobeCalloutCue.restore(saved, "new-process"))
        assertEquals(GlobeCalloutCue(), GlobeCalloutCue.restore(listOf("owned-process", 1, false, false, true, "not-a-ticket", true), "owned-process"))
    }
}
