package com.foxhole.vpn.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterHomeAutomationServicePolicyTest {
    @Test fun onlyAnExplicitOwnedStartCanAttachMonitoring() {
        assertEquals(RouterHomeServiceCommand.START, routerHomeServiceCommand(RouterHomeAutomationService.ACTION_START, true))
        assertEquals(RouterHomeServiceCommand.IGNORE, routerHomeServiceCommand(RouterHomeAutomationService.ACTION_START, false))
        assertEquals(RouterHomeServiceCommand.IGNORE, routerHomeServiceCommand(null, true))
        assertEquals(RouterHomeServiceCommand.IGNORE, routerHomeServiceCommand("android.intent.action.BOOT_COMPLETED", true))
    }

    @Test fun quickDisableRequiresTheCurrentRequestAndOldStopCannotDisableANewSession() {
        assertEquals(RouterHomeServiceCommand.STOP, routerHomeServiceCommand(RouterHomeAutomationService.ACTION_STOP, true))
        assertEquals(RouterHomeServiceCommand.IGNORE, routerHomeServiceCommand(RouterHomeAutomationService.ACTION_STOP, false))
        assertEquals(RouterHomeServiceCommand.STOP,
            routerHomeServiceCommand(RouterHomeAutomationService.ACTION_STOP, ownedRequest = true, startEligible = false))
        assertEquals(RouterHomeServiceCommand.IGNORE,
            routerHomeServiceCommand(RouterHomeAutomationService.ACTION_START, ownedRequest = true, startEligible = false))
        assertEquals(RouterHomeServiceCommand.START, routerHomeServiceCommand(RouterHomeAutomationService.ACTION_START, true))
    }

    @Test fun deniedNotificationPermissionKeepsModernMonitoringOff() {
        for (sdk in listOf(33, 35, 37)) {
            assertFalse(routerHomeNotificationAllowed(sdk, permissionGranted = false, appEnabled = true, channelImportance = 2))
            assertTrue(routerHomeNotificationAllowed(sdk, permissionGranted = true, appEnabled = true, channelImportance = 2))
        }
    }

    @Test fun explicitOffKeepsForegroundDuringResumeThenClosesOnlyItsCurrentSession() {
        assertEquals(RouterHomeStatusAction.UPDATE,
            routerHomeStatusAction(true, true, false, RouterHomeMonitorState.MONITORING))
        assertEquals(RouterHomeStatusAction.IGNORE,
            routerHomeStatusAction(true, true, true, RouterHomeMonitorState.MONITORING))
        assertEquals(RouterHomeStatusAction.FINISH,
            routerHomeStatusAction(true, true, false, RouterHomeMonitorState.OFF))
        assertEquals(RouterHomeStatusAction.IGNORE,
            routerHomeStatusAction(false, true, false, RouterHomeMonitorState.OFF))
        assertEquals(RouterHomeStatusAction.IGNORE,
            routerHomeStatusAction(true, false, false, RouterHomeMonitorState.OFF))
        assertEquals(RouterHomeStatusAction.UPDATE,
            routerHomeStatusAction(true, true, false, RouterHomeMonitorState.MONITORING))
    }

    @Test fun legacyAndroidDoesNotRequirePostNotificationsButStillHonorsAppAndChannelBlocks() {
        assertTrue(routerHomeNotificationAllowed(26, permissionGranted = false, appEnabled = true, channelImportance = 2))
        assertFalse(routerHomeNotificationAllowed(26, permissionGranted = false, appEnabled = false, channelImportance = 2))
        for (importance in listOf(0, 1)) {
            assertFalse(routerHomeNotificationAllowed(37, permissionGranted = true, appEnabled = true, channelImportance = importance))
        }
    }
}
