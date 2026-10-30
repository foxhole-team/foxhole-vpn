package com.foxhole.vpn.router

import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class RouterPhoneLeaseKeeperTest {
    private val owner = RouterPhoneSession("profile", 3, 19)
    private class Phone : RouterHomePhoneController {
        override val changes = MutableStateFlow(VpnUiState(phase = VpnPhase.CONNECTED, selectedProfileId = "profile", profileRevision = 3, connectionId = 19, hasOwnedTunnel = true))
        override val manualGeneration = MutableStateFlow(0L)
        override fun snapshot(): RouterHomePhoneSnapshot {
            val state = changes.value
            val session = state.connectionId?.let { RouterPhoneSession(requireNotNull(state.selectedProfileId), state.profileRevision, it) }
            return RouterHomePhoneSnapshot(state.selectedProfileId, state.profileRevision, session.takeIf { state.isConnected },
                state.phase == VpnPhase.IDLE, session.takeIf { state.phase in setOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING) })
        }
        override fun pause(owner: RouterAutomaticPause): RouterPausedPhone? = error("lease renewal cannot pause")
        override fun resume(receipt: RouterPausedPhone, plan: RouterBoundDialPlan?): RouterPhoneSession? = error("lease renewal cannot start")
        override suspend fun dialCandidate(owner: RouterPhoneSession, resolver: suspend (String) -> List<InetAddress>): RouterDialCandidate = error("renewal cannot resolve another plan")
        override fun forget(receipt: RouterPausedPhone) = Unit
    }
    private class Link(owner: RouterPhoneSession, private val now: () -> Long) : RouterHomeLink {
        override val pairingId = "pair"
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val hasPhoneLease = true
        override val phoneLeaseOwner = owner
        override var phoneLeaseDeadline: Long? = now() + 60000
        var wifi = true
        var closed = false
        var renewals = 0
        var failures = false
        override fun wifiIsPaired() = wifi
        override suspend fun initialize() = Unit
        override suspend fun status(): RouterPhoneStatus = error("renewal does not claim router proof")
        override suspend fun validatePhoneResume(owner: RouterPhoneSession) = error("no new preparation")
        override suspend fun routedAfterLeaseRelease(proof: RouterHomeProof, expectedOwner: RouterPhoneSession?, stillOwned: () -> Boolean): RouterHomeProof? = error("no router handoff")
        override suspend fun leaseForPhone(owner: RouterPhoneSession): RouterBoundDialPlan = error("no new lease")
        override suspend fun phoneStarted(claimId: String, owner: RouterPhoneSession) = Unit
        override suspend fun renewPhoneLease(owner: RouterPhoneSession, stillOwned: () -> Boolean) {
            if (!stillOwned() || owner != phoneLeaseOwner) return
            renewals++
            if (failures) throw RouterApiFailure(RouterApiError.NETWORK)
            phoneLeaseDeadline = now() + 60000
        }
        override fun close() { closed = true }
    }

    @Test fun renewsTheExactNativeConnectionWithoutKeepingHomeMonitoringEnabled() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        assertTrue(keeper.retain(link, owner, 0))
        runCurrent()
        advanceTimeBy(20000); runCurrent()
        assertTrue(link.renewals >= 2)
        assertEquals(owner, keeper.owner)
        phone.changes.value = phone.changes.value.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null)
        runCurrent()
        assertTrue(link.closed)
        assertEquals(null, keeper.owner)
    }

    @Test fun aManualCommandForTheSameProfileCannotInheritTheOldLease() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        keeper.retain(link, owner, 0); runCurrent()
        val prior = link.renewals
        phone.manualGeneration.value = 1
        runCurrent()
        assertTrue(link.closed)
        advanceTimeBy(60000); runCurrent()
        assertEquals(prior, link.renewals)
    }

    @Test fun changedRevisionOrAnotherCommandTicketCannotKeepAnOwnedLeaseAlive() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        phone.changes.value = phone.changes.value.copy(profileRevision = 4)
        assertFalse(keeper.retain(link, owner, 0))
        phone.changes.value = phone.changes.value.copy(profileRevision = 3, phase = VpnPhase.CONNECTING, connectionId = 20)
        assertFalse(keeper.retain(link, owner, 0))
        assertEquals(0, link.renewals)
    }

    @Test fun aReservedStartHasOnlyThirtySecondsToBecomeTheActualOwnedNativeTunnel() = runTest {
        val phone = Phone().apply { changes.value = changes.value.copy(phase = VpnPhase.CONNECTING) }
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        assertTrue(keeper.retain(link, owner, 0)); runCurrent()
        advanceTimeBy(29999); runCurrent()
        assertFalse(link.closed)
        advanceTimeBy(1); runCurrent()
        assertTrue(link.closed)
    }

    @Test fun anExplicitNewHomeAdmissionCanAdoptTheSamePinnedLinkWithoutClosingIt() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        keeper.retain(link, owner, 0); runCurrent()
        assertSame(link, keeper.borrow())
        assertTrue(keeper.adopt(link)); runCurrent()
        assertFalse(link.closed)
        assertEquals(null, keeper.owner)
        assertFalse(keeper.owns(link))
        link.close()
    }

    @Test fun permissionRevocationOrLeavingWifiClosesTheLeaseWithoutCellularFallback() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        var permission = true
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { permission })
        keeper.retain(link, owner, 0); runCurrent()
        val prior = link.renewals
        permission = false
        link.changes.tryEmit(Unit); runCurrent()
        assertTrue(link.closed)
        assertEquals(prior, link.renewals)
        val offline = Link(owner) { 10000 + testScheduler.currentTime }.apply { wifi = false }
        val permitted = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        permitted.retain(offline, owner, 0); runCurrent()
        assertEquals(0, offline.renewals)
        assertTrue(offline.closed)
    }

    @Test fun aTemporaryFailureCanRetryOnlyInsideThePreviouslyAcknowledgedLeaseDeadline() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }.apply { failures = true }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        keeper.retain(link, owner, 0); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertFalse(link.closed)
        assertTrue(link.renewals >= 2)
        advanceTimeBy(55000); runCurrent()
        assertTrue(link.closed)
        assertEquals(null, keeper.owner)
    }

    @Test fun aBorrowedLinkCannotBeAdoptedAfterItsNativeOwnerOrDeadlineIsLost() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        keeper.retain(link, owner, 0); runCurrent()
        assertSame(link, keeper.borrow())
        phone.manualGeneration.value++
        assertFalse(keeper.adopt(link))
        runCurrent()
        assertFalse(keeper.adopt(link))
        assertTrue(link.closed)
    }


    @Test fun theInitialExactNativeValidationCanFinishWithoutRetiringItsLease() = runTest {
        val phone = Phone().apply { changes.value = changes.value.copy(phase = VpnPhase.CONNECTING) }
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        keeper.retain(link, owner, 0); runCurrent()
        advanceTimeBy(1000)
        phone.changes.value = phone.changes.value.copy(phase = VpnPhase.VALIDATING); runCurrent()
        assertFalse(link.closed)
        assertEquals(owner, keeper.owner)
        phone.changes.value = phone.changes.value.copy(phase = VpnPhase.CONNECTED); runCurrent()
        advanceTimeBy(60000); runCurrent()
        assertFalse(link.closed)
        assertTrue(link.renewals > 2)
        phone.manualGeneration.value++; runCurrent()
    }

    @Test fun anAlreadyConnectedNativeCanRevalidateOnlyForABoundedSameTicketWindow() = runTest {
        val phone = Phone()
        val link = Link(owner) { 10000 + testScheduler.currentTime }
        val keeper = RouterPhoneLeaseKeeper(this, phone, { 10000 + testScheduler.currentTime }, { true })
        keeper.retain(link, owner, 0); runCurrent()
        advanceTimeBy(20000); runCurrent()
        phone.changes.value = phone.changes.value.copy(phase = VpnPhase.VALIDATING); runCurrent()
        advanceTimeBy(29999); runCurrent()
        assertFalse(link.closed)
        advanceTimeBy(1); runCurrent()
        assertTrue(link.closed)
        assertEquals(null, keeper.owner)
    }

}
