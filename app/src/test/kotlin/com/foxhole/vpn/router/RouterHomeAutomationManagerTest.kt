package com.foxhole.vpn.router

import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class RouterHomeAutomationManagerTest {
    private class Phone : RouterHomePhoneController {
        override val changes = MutableStateFlow(VpnUiState(isReady = true, selectedProfileId = "profile", profileRevision = 3,
            phase = VpnPhase.CONNECTED, hasOwnedTunnel = true, connectionId = 17))
        override val manualGeneration = MutableStateFlow(0L)
        var paused: RouterPausedPhone? = null
        var pauses = 0
        var resumes = 0
        override fun snapshot() = RouterHomePhoneSnapshot("profile", 3,
            changes.value.connectionId?.let { RouterPhoneSession("profile", 3, it) }.takeIf { changes.value.isConnected }, changes.value.phase == VpnPhase.IDLE,
            changes.value.connectionId?.let { RouterPhoneSession("profile", 3, it) }.takeIf { changes.value.phase in setOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING) })
        override fun pause(owner: RouterAutomaticPause): RouterPausedPhone {
            pauses++
            return RouterPausedPhone(owner, 18, manualGeneration.value).also {
                paused = it; changes.value = changes.value.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null)
            }
        }
        override fun resume(receipt: RouterPausedPhone, plan: RouterBoundDialPlan?): RouterPhoneSession? {
            if (paused != receipt || manualGeneration.value != receipt.manualGeneration) return null
            resumes++; paused = null; changes.value = changes.value.copy(phase = VpnPhase.CONNECTING, connectionId = 19)
            return RouterPhoneSession("profile", 3, 19)
        }
        override suspend fun dialCandidate(owner: RouterPhoneSession, resolver: suspend (String) -> List<InetAddress>): RouterDialCandidate = error("not needed outside home")
        override fun forget(receipt: RouterPausedPhone) { if (paused == receipt) paused = null }
        fun manualStart() { manualGeneration.value++; paused = null; changes.value = changes.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true, connectionId = 20) }
        fun manualStop() { manualGeneration.value++; paused = null; changes.value = changes.value.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false) }
    }

    private class Link(private val now: () -> Long) : RouterHomeLink {
        override val pairingId = "pair"
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val hasPhoneLease get() = phoneLeaseOwner != null
        override var phoneLeaseOwner: RouterPhoneSession? = null
        override val phoneLeaseDeadline: Long? get() = phoneLeaseOwner?.let { now() + 60000 }
        var releaseCount = 0
        var blockValidation: CompletableDeferred<Unit>? = null
        var releaseHook: (() -> Unit)? = null
        var leaseHook: (() -> Unit)? = null
        var leaseAvailable = false
        var statusFailure: Exception? = null
        var validationFailure: Exception? = null
        var leaseFailure: Exception? = null
        var historicalHandoff: RouterPhoneHandoffStatus? = null
        var blockInitialize: CompletableDeferred<Unit>? = null
        var wifi = true
        var wifiChecks = 0
        var proof = true
        var closed = false
        var blockLease: CompletableDeferred<Unit>? = null
        override fun wifiIsPaired(): Boolean { wifiChecks++; return wifi }
        override suspend fun initialize() { blockInitialize?.await() }
        override suspend fun status(): RouterPhoneStatus {
            statusFailure?.let { throw it }
            return RouterPhoneStatus(pairingId, "device", "192.168.1.50", fresh().takeIf { proof }, handoff = historicalHandoff, handoffKnown = true)
        }
        fun fresh() = RouterHomeProof(pairingId, "server", "generation", now(), true, true)
        override suspend fun validatePhoneResume(owner: RouterPhoneSession) {
            validationFailure?.let { throw it }
            blockValidation?.await()
        }
        override suspend fun routedAfterLeaseRelease(proof: RouterHomeProof, expectedOwner: RouterPhoneSession?, stillOwned: () -> Boolean): RouterHomeProof? {
            if (!routerLeaseReleaseOwned(phoneLeaseOwner, expectedOwner, stillOwned()) ||
                !routerHandoffCanRelease(status(), "a".repeat(32).takeIf { phoneLeaseOwner != null })) return null
            releaseCount++; phoneLeaseOwner = null
            releaseHook?.invoke()
            return proof
        }
        override suspend fun leaseForPhone(owner: RouterPhoneSession): RouterBoundDialPlan {
            blockLease?.await()
            leaseFailure?.let { throw it }
            if (!leaseAvailable) error("lease unavailable")
            phoneLeaseOwner = owner
            leaseHook?.invoke()
            val profile = com.foxhole.vpn.profile.VpnProfileImporter().import("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls&sni=example.com#Fixture").profiles.single()
            return RouterBoundDialPlan(owner, routerProfileDigest(profile), com.foxhole.core.runtime.VpnEngineConfig.prepare(profile),
                listOf(RouterUpstreamTarget("1.1.1.1", 443, "tcp")), "a".repeat(32), now() + 60000)
        }
        override suspend fun renewPhoneLease(owner: RouterPhoneSession, stillOwned: () -> Boolean) = Unit
        override suspend fun phoneStarted(claimId: String, owner: RouterPhoneSession) { phoneLeaseOwner = owner }
        override fun close() { closed = true; phoneLeaseOwner = null }
    }

    private class Fixture(val scope: TestScope, accepted: Boolean = true, retainLease: Boolean = false) {
        val phone = Phone()
        val link = Link { 10000 + scope.testScheduler.currentTime }
        var epoch = 0L
        var grants = true
        var persistedOff = 0
        var preparationInstalled = false
        var persistedOn = false
        var failPersistOn = false
        val retained = RouterPhoneLeaseKeeper(scope, phone, { 10000 + scope.testScheduler.currentTime }, { grants })
        val manager = RouterHomeAutomationManager(accepted, scope, phone, { link },
            { 10000 + scope.testScheduler.currentTime }, { true }, { grants }, { grants }, { epoch = it }, {},
            { persistedOff++; persistedOn = false }, { preparationInstalled = it != null }, persistOn = {
                if (failPersistOn) throw java.io.IOException("fixture")
                persistedOn = true
            }, retainedLeases = retained.takeIf { retainLease })
        suspend fun start() { manager.setEnabled(true); assertTrue(manager.foregroundAttached(epoch)); scope.runCurrent() }
    }

    @Test fun scopedBootstrapPreparationKeepsThePhoneConnectedAndReportsItsUnsupportedProfile() = runTest {
        val fixture = Fixture(this)
        fixture.link.validationFailure = RouterBootstrapDialUnsupported()
        val before = fixture.phone.changes.value
        fixture.start()
        assertEquals(0, fixture.phone.pauses)
        assertEquals(0, fixture.phone.resumes)
        assertEquals(0, fixture.link.releaseCount)
        assertEquals(before, fixture.phone.changes.value)
        assertEquals(RouterHomeMonitorState.PROFILE_UNSUPPORTED, fixture.manager.status.value)
        assertTrue(fixture.manager.isEnabled)
        advanceTimeBy(5000); runCurrent()
        assertEquals(RouterHomeMonitorState.PROFILE_UNSUPPORTED, fixture.manager.status.value)
        fixture.link.validationFailure = null
        fixture.link.changes.tryEmit(Unit); runCurrent()
        assertEquals(1, fixture.phone.pauses)
        assertEquals(RouterHomeMonitorState.MONITORING, fixture.manager.status.value)
        fixture.manager.setEnabled(false)
    }

    @Test fun genericUnsupportedRouterFailureDoesNotClaimAnUnsupportedProfile() = runTest {
        val fixture = Fixture(this)
        fixture.link.validationFailure = RouterApiFailure(RouterApiError.UNSUPPORTED)
        fixture.start()
        assertEquals(0, fixture.phone.pauses)
        assertEquals(RouterHomeMonitorState.ROUTER_UNAVAILABLE, fixture.manager.status.value)
        fixture.manager.setEnabled(false)
    }

    @Test fun changingTheRouterClosesItsSessionWithoutClearingItsSavedPreferenceOrThePhoneProfile() = runTest {
        val fixture = Fixture(this)
        fixture.link.validationFailure = RouterBootstrapDialUnsupported()
        fixture.start()
        val before = fixture.phone.changes.value
        val oldEpoch = fixture.epoch
        fixture.manager.routerSelectionChanged()
        assertTrue(fixture.link.closed)
        assertTrue(fixture.persistedOn)
        assertEquals(0, fixture.persistedOff)
        assertEquals(before, fixture.phone.changes.value)
        assertFalse(fixture.manager.isCurrentRequest(oldEpoch))
        fixture.manager.foregroundStopped(oldEpoch)
        runCurrent()
        assertEquals(0, fixture.persistedOff)
    }

    @Test fun manualPhoneStartKeepsItsNormalNativePreparationWhenAutomaticBootstrapIsUnsupported() = runTest {
        val fixture = Fixture(this)
        fixture.link.validationFailure = RouterBootstrapDialUnsupported()
        fixture.link.leaseFailure = RouterBootstrapDialUnsupported()
        fixture.start()
        val before = fixture.phone.changes.value
        assertEquals(null, fixture.manager.preparePhoneStart(RouterPhoneSession("profile", 3, 17)))
        assertEquals(before, fixture.phone.changes.value)
        assertEquals(0, fixture.phone.pauses)
        assertEquals(0, fixture.phone.resumes)
        assertEquals(RouterHomeMonitorState.PROFILE_UNSUPPORTED, fixture.manager.status.value)
        fixture.phone.manualStart(); runCurrent()
        assertEquals(RouterHomeMonitorState.PROFILE_UNSUPPORTED, fixture.manager.status.value)
        fixture.manager.setEnabled(false)
    }

    @Test fun disabledOrUnacceptedFeatureCannotStartMonitoringFromBootPreferences() = runTest {
        val fixture = Fixture(this, accepted = false)
        assertFalse(fixture.manager.canForegroundAttach(0))
        try { fixture.manager.setEnabled(true); error("unaccepted feature started") } catch (_: RouterSetupFailure) { }
        assertEquals(0L, fixture.epoch)
        assertEquals(0, fixture.phone.pauses)
    }

    @Test fun foregroundAttachUsesTheExactLiveEpochAndDuplicateStartKeepsOneLoop() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        assertFalse(fixture.manager.canForegroundAttach(fixture.epoch - 1))
        assertTrue(fixture.manager.foregroundAttached(fixture.epoch))
        runCurrent()
        assertEquals(1, fixture.phone.pauses)
        assertTrue(fixture.preparationInstalled)
        fixture.manager.setEnabled(false)
        assertFalse(fixture.preparationInstalled)
    }

    @Test fun leavingHomeResumesOnlyTheConnectionAutomaticallyPausedByThisManager() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        assertEquals(1, fixture.phone.pauses)
        fixture.link.wifi = false
        fixture.link.changes.tryEmit(Unit)
        runCurrent()
        assertEquals(1, fixture.phone.resumes)
        fixture.manager.setEnabled(false)
    }

    @Test fun manualStopRevokesResumeEvenWhenThePhoneLeavesHome() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.phone.manualStop()
        runCurrent()
        fixture.link.wifi = false; fixture.link.changes.tryEmit(Unit)
        runCurrent()
        assertEquals(0, fixture.phone.resumes)
        fixture.manager.setEnabled(false)
    }

    @Test fun aSingleMissingProofDoesNotFlapAndFailedLeasePreparationRetainsOwnedPause() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.link.proof = false
        advanceTimeBy(5000); runCurrent()
        assertEquals(0, fixture.phone.resumes)
        advanceTimeBy(30000); runCurrent()
        assertEquals(0, fixture.phone.resumes)
        assertTrue(fixture.phone.paused != null)
        fixture.link.wifi = false; fixture.link.changes.tryEmit(Unit)
        runCurrent()
        assertEquals(1, fixture.phone.resumes)
        fixture.manager.setEnabled(false)
    }

    @Test fun permissionRevocationStopsOnlyOwnedMonitoringAndPersistsOff() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.grants = false
        advanceTimeBy(5000); runCurrent()
        assertEquals(RouterHomeMonitorState.OFF, fixture.manager.status.value)
        assertTrue(fixture.link.closed)
        assertFalse(fixture.manager.isEnabled)
        assertTrue(fixture.persistedOff > 0)
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test fun anOldNotificationStopWaitingBehindANewEnableCannotRetireTheNewEpoch() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        val oldEpoch = fixture.epoch
        val release = CompletableDeferred<Unit>()
        fixture.link.blockLease = release
        val off = launch { fixture.manager.setEnabled(false) }
        runCurrent()
        val newer = launch { fixture.manager.setEnabled(true) }
        runCurrent()
        fixture.manager.disableFromService(oldEpoch)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(off.isCompleted); assertTrue(newer.isCompleted)
        assertTrue(fixture.epoch > oldEpoch)
        assertTrue(fixture.manager.isEnabled)
        assertEquals(RouterHomeMonitorState.MONITORING, fixture.manager.status.value)
        fixture.manager.setEnabled(false)
    }

    @Test fun anOlderPauseCannotReleaseTheLeaseOfANewerManualStart() = runTest {
        val fixture = Fixture(this)
        val validated = CompletableDeferred<Unit>()
        fixture.link.blockValidation = validated
        fixture.manager.setEnabled(true)
        fixture.manager.foregroundAttached(fixture.epoch)
        runCurrent()
        fixture.phone.manualStart()
        val newer = requireNotNull(fixture.phone.snapshot().connected)
        fixture.link.phoneLeaseOwner = newer
        validated.complete(Unit)
        runCurrent()
        assertEquals(newer, fixture.link.phoneLeaseOwner)
        assertEquals(0, fixture.link.releaseCount)
        assertEquals(0, fixture.phone.pauses)
        fixture.manager.setEnabled(false)
    }

    @Test fun retiringDuringTheFinalProofReturnCannotPublishAPauseAfterMonitorOff() = runTest {
        val fixture = Fixture(this)
        fixture.link.releaseHook = { fixture.manager.foregroundStopped(fixture.epoch) }
        fixture.start()
        assertEquals(0, fixture.phone.pauses)
        assertEquals(RouterHomeMonitorState.OFF, fixture.manager.status.value)
        assertFalse(fixture.manager.isEnabled)
    }

    @Test fun retiringDuringTheFinalResumePlanReturnCannotStartThePhone() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.link.proof = false
        fixture.link.leaseAvailable = true
        fixture.link.leaseHook = { fixture.manager.foregroundStopped(fixture.epoch) }
        advanceTimeBy(35000); runCurrent()
        assertEquals(0, fixture.phone.resumes)
        assertEquals(RouterHomeMonitorState.OFF, fixture.manager.status.value)
    }

    @Test fun anAcceptedResumeBindsTheLeaseToItsActualNewCommandBeforeAnotherPause() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.link.proof = false
        fixture.link.leaseAvailable = true
        advanceTimeBy(35000); runCurrent()
        val resumed = RouterPhoneSession("profile", 3, 19)
        assertEquals(1, fixture.phone.resumes)
        assertEquals(resumed, fixture.link.phoneLeaseOwner)
        fixture.link.proof = true
        fixture.phone.changes.value = fixture.phone.changes.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        runCurrent()
        assertEquals(2, fixture.phone.pauses)
        assertEquals(null, fixture.link.phoneLeaseOwner)
        fixture.link.wifi = false
        fixture.manager.setEnabled(false)
    }

    @Test fun anotherWifiWithTheSamePrivateAddressResumesOnlyTheOwnedPause() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.link.statusFailure = RouterHomeNotPairedNetwork()
        fixture.link.changes.tryEmit(Unit)
        runCurrent()
        assertEquals(1, fixture.phone.resumes)
        assertTrue(fixture.manager.isEnabled)
        fixture.link.wifi = false
        fixture.manager.setEnabled(false)
    }

    @Test fun aRevokedSavedPairingStopsMonitoringWithoutStartingAnUnownedConnection() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.link.statusFailure = RouterHomePairingChanged()
        fixture.link.changes.tryEmit(Unit)
        runCurrent()
        assertEquals(0, fixture.phone.resumes)
        assertEquals(RouterHomeMonitorState.OFF, fixture.manager.status.value)
        assertFalse(fixture.persistedOn)
    }

    @Test fun admissionPersistsOutsideSheetLifetimeAndPersistenceFailureCannotStartMonitoring() = runTest {
        val fixture = Fixture(this)
        fixture.manager.setEnabled(true)
        assertTrue(fixture.persistedOn)
        assertTrue(fixture.manager.isEnabled)
        fixture.manager.setEnabled(false)
        val failed = Fixture(this).apply { failPersistOn = true }
        try { failed.manager.setEnabled(true); error("persistence failure was accepted") } catch (_: java.io.IOException) { }
        assertFalse(failed.manager.isEnabled)
        assertFalse(failed.persistedOn)
        assertEquals(RouterHomeMonitorState.OFF, failed.manager.status.value)
        assertTrue(failed.link.closed)
    }


    @Test fun explicitAutoOffTransfersTheResumedNativeLeaseWithoutKeepingHomeMonitoringOn() = runTest {
        val fixture = Fixture(this, retainLease = true)
        fixture.start()
        fixture.link.leaseAvailable = true
        fixture.manager.setEnabled(false)
        runCurrent()
        assertEquals(RouterHomeMonitorState.OFF, fixture.manager.status.value)
        assertFalse(fixture.persistedOn)
        assertFalse(fixture.link.closed)
        assertEquals(RouterPhoneSession("profile", 3, 19), fixture.retained.owner)
        assertEquals(1, fixture.phone.resumes)
        fixture.phone.changes.value = fixture.phone.changes.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        runCurrent()
        fixture.phone.manualStop(); runCurrent()
        assertTrue(fixture.link.closed)
        assertEquals(null, fixture.retained.owner)
    }

    @Test fun failedHomeReadmissionDoesNotCloseTheLeaseOfTheStillActiveNativePhone() = runTest {
        val fixture = Fixture(this, retainLease = true)
        fixture.start()
        fixture.link.leaseAvailable = true
        fixture.manager.setEnabled(false)
        fixture.phone.changes.value = fixture.phone.changes.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        runCurrent()
        fixture.failPersistOn = true
        try { fixture.manager.setEnabled(true); error("failed admission accepted") } catch (_: java.io.IOException) { }
        runCurrent()
        assertFalse(fixture.manager.isEnabled)
        assertFalse(fixture.link.closed)
        assertEquals(RouterPhoneSession("profile", 3, 19), fixture.retained.owner)
        fixture.phone.manualStop(); runCurrent()
        assertTrue(fixture.link.closed)
    }


    @Test fun losingTheBorrowedNativeLeaseDuringHomeAdmissionCannotStartAClosedMonitor() = runTest {
        val fixture = Fixture(this, retainLease = true)
        fixture.start()
        fixture.link.leaseAvailable = true
        fixture.manager.setEnabled(false)
        fixture.phone.changes.value = fixture.phone.changes.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        runCurrent()
        val release = CompletableDeferred<Unit>()
        fixture.link.blockInitialize = release
        var error: RouterWizardError? = null
        val admitted = launch {
            try { fixture.manager.setEnabled(true) } catch (failure: RouterSetupFailure) { error = failure.kind }
        }
        runCurrent()
        fixture.phone.manualStop(); runCurrent()
        assertTrue(fixture.link.closed)
        release.complete(Unit); runCurrent()
        assertTrue(admitted.isCompleted)
        assertEquals(RouterWizardError.UNAVAILABLE, error)
        assertFalse(fixture.manager.isEnabled)
        assertEquals(RouterHomeMonitorState.OFF, fixture.manager.status.value)
        assertFalse(fixture.persistedOn)
    }


    @Test fun aHistoricalUnknownRouterLeaseCannotBeReleasedOrMistakenForACompletedPause() = runTest {
        val fixture = Fixture(this)
        fixture.link.historicalHandoff = RouterPhoneHandoffStatus("b".repeat(32), 70000, 3)
        fixture.start()
        assertEquals(0, fixture.phone.pauses)
        assertEquals(0, fixture.link.releaseCount)
        fixture.link.historicalHandoff = null
        fixture.link.changes.tryEmit(Unit); runCurrent()
        assertEquals(1, fixture.phone.pauses)
        assertEquals(1, fixture.link.releaseCount)
        fixture.link.wifi = false
        fixture.manager.setEnabled(false)
    }


    @Test fun explicitAutoOffWhileTheOwnedNativeValidatesPreservesItsExactLease() = runTest {
        val fixture = Fixture(this, retainLease = true)
        fixture.start()
        fixture.link.proof = false; fixture.link.leaseAvailable = true
        advanceTimeBy(35000); runCurrent()
        assertEquals(1, fixture.phone.resumes)
        fixture.phone.changes.value = fixture.phone.changes.value.copy(phase = VpnPhase.VALIDATING, hasOwnedTunnel = true)
        runCurrent()
        fixture.manager.setEnabled(false); runCurrent()
        assertFalse(fixture.manager.isEnabled)
        assertFalse(fixture.link.closed)
        assertEquals(RouterPhoneSession("profile", 3, 19), fixture.retained.owner)
        fixture.phone.changes.value = fixture.phone.changes.value.copy(phase = VpnPhase.CONNECTED); runCurrent()
        advanceTimeBy(60000); runCurrent()
        assertFalse(fixture.link.closed)
        fixture.phone.manualStop(); runCurrent()
        assertTrue(fixture.link.closed)
    }


    @Test fun awayFromHomeUsesABoundedSlowWaitAndNetworkEventsStillWakeImmediately() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.link.wifi = false; fixture.link.changes.tryEmit(Unit); runCurrent()
        val checks = fixture.link.wifiChecks
        advanceTimeBy(29999); runCurrent()
        assertEquals(checks, fixture.link.wifiChecks)
        advanceTimeBy(1); runCurrent()
        assertTrue(fixture.link.wifiChecks > checks)
        val beforeEvent = fixture.link.wifiChecks
        fixture.link.changes.tryEmit(Unit); runCurrent()
        assertTrue(fixture.link.wifiChecks > beforeEvent)
        fixture.manager.setEnabled(false)
    }

}
