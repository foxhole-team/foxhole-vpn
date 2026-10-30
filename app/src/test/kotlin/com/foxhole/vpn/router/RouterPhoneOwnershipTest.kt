package com.foxhole.vpn.router

import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterPhoneOwnershipTest {
    private val owner = RouterPhoneSession("profile", 3, 17)
    private val ui = VpnUiState(isReady = true, selectedProfileId = "profile", profileRevision = 3,
        phase = VpnPhase.CONNECTED, hasOwnedTunnel = true, connectionId = 17)
    private val runtime = EngineSnapshot(phase = EnginePhase.CONNECTED, hasOwnedTunnel = true, profileId = "profile", commandTicket = 17)
    private val receipt = RouterPausedPhone(RouterAutomaticPause(owner, "paired"), 18, 2)
    private val idleUi = ui.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null)
    private val idleRuntime = EngineSnapshot()

    @Test fun onlyTheActualCurrentConnectedTunnelCanBePaused() {
        assertEquals(owner, routerConnectedPhone(ui, runtime, 17, false, true))
        assertNull(routerConnectedPhone(ui, runtime, 18, false, true))
        assertNull(routerConnectedPhone(ui, runtime.copy(profileId = "another"), 17, false, true))
        assertNull(routerConnectedPhone(ui, runtime, 17, true, true))
        assertNull(routerConnectedPhone(ui, runtime, 17, false, false))
    }

    @Test fun bootOrAnOriginallyIdlePhoneHasNoPauseReceiptToResume() {
        assertFalse(routerResumeOwned(receipt, null, idleUi, idleRuntime, 18, 2, false, true, false))
    }

    @Test fun resumeIsBoundToTheAutomaticPauseAndManualCommandGeneration() {
        assertTrue(routerResumeOwned(receipt, receipt, idleUi, idleRuntime, 18, 2, false, true, false))
        assertFalse(routerResumeOwned(receipt, receipt, idleUi, idleRuntime, 19, 2, false, true, false))
        assertFalse(routerResumeOwned(receipt, receipt, idleUi, idleRuntime, 18, 3, false, true, false))
        assertFalse(routerResumeOwned(receipt, receipt, idleUi.copy(profileRevision = 4), idleRuntime, 18, 2, false, true, false))
        assertFalse(routerResumeOwned(receipt, receipt, idleUi.copy(selectedProfileId = "other"), idleRuntime, 18, 2, false, true, false))
    }

    @Test fun resumeCannotReplaceAnotherVpnOrInterruptAPendingTunnel() {
        assertFalse(routerResumeOwned(receipt, receipt, idleUi, idleRuntime, 18, 2, false, true, true))
        assertFalse(routerResumeOwned(receipt, receipt, idleUi, idleRuntime, 18, 2, true, true, false))
        assertFalse(routerResumeOwned(receipt, receipt, idleUi, runtime, 18, 2, false, true, false))
    }

    @Test fun automaticRefreshDefersUntilTheExactOwnedPauseIsCleared() {
        assertTrue(routerAutomaticProfilesProtected(receipt, 18, 2, "profile", 3))
        assertFalse(routerAutomaticProfilesProtected(null, 18, 2, "profile", 3))
        assertFalse(routerAutomaticProfilesProtected(receipt, 18, 3, "profile", 3))
        assertFalse(routerAutomaticProfilesProtected(receipt, 19, 2, "profile", 3))
        assertTrue(routerResumeOwned(receipt, receipt, idleUi, idleRuntime, 18, 2, false, true, false))
    }

    @Test fun automaticRefreshAlsoDefersWhileAnExactOwnedLeaseSuppliesTheActiveNativePlan() {
        assertTrue(routerAutomaticLeaseProtected(owner, 17, "profile", 3, true))
        assertFalse(routerAutomaticLeaseProtected(owner, 18, "profile", 3, true))
        assertFalse(routerAutomaticLeaseProtected(owner, 17, "profile", 4, true))
        assertFalse(routerAutomaticLeaseProtected(owner, 17, "profile", 3, false))
        assertFalse(routerAutomaticLeaseProtected(null, 17, "profile", 3, true))
    }


    @Test fun reservedPreparedStartProtectsAutomaticRefreshWhileLeaseOwnerRemappingIsSuspended() {
        val profile = com.foxhole.vpn.profile.VpnProfileImporter().import("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls&sni=example.com#Fixture").profiles.single()
        val oldOwner = RouterPhoneSession(profile.id, 3, 17)
        val plan = prepareRouterDialCandidate(profile, oldOwner) { error("literal") }.leased("a".repeat(32), 60000)
        assertFalse(routerAutomaticLeaseProtected(oldOwner, 19, profile.id, 3, true))
        assertTrue(routerAutomaticPreparedLeaseProtected(19L to plan, 19, profile.id, 3, true, 10000))
        assertFalse(routerAutomaticPreparedLeaseProtected(19L to plan, 20, profile.id, 3, true, 10000))
        assertFalse(routerAutomaticPreparedLeaseProtected(19L to plan, 19, profile.id, 4, true, 10000))
        assertFalse(routerAutomaticPreparedLeaseProtected(19L to plan, 19, profile.id, 3, false, 10000))
        assertFalse(routerAutomaticPreparedLeaseProtected(19L to plan, 19, profile.id, 3, true, 60000))
    }

}
