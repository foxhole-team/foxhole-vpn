package com.foxhole.vpn.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineNetworkReadinessTest {
    @Test fun visibleOwnedNetworkIsNotDeliveredBeforeSocketBindingIsAllowed() = runTest {
        var allowed = false
        val pending = async { awaitAccessibleVpnNetwork({ 1L }, { allowed }) }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertFalse(pending.isCompleted)
        allowed = true
        advanceTimeBy(50)
        runCurrent()
        assertEquals(1L, pending.await())
    }

    @Test fun aDisappearingCandidateIsNotRetainedDuringReadinessWait() = runTest {
        var candidate: Long? = 1L
        val pending = async { awaitAccessibleVpnNetwork({ candidate }, { it == 2L }) }
        runCurrent()
        candidate = null
        advanceTimeBy(100)
        runCurrent()
        assertFalse(pending.isCompleted)
        candidate = 2L
        advanceTimeBy(50)
        runCurrent()
        assertEquals(2L, pending.await())
    }

    @Test fun permanentlyInaccessibleNetworkExpiresWithoutBeingReturned() = runTest {
        assertNull(awaitAccessibleVpnNetwork({ 1L }, { false }))
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun stopCancellationEndsPollingWithoutWaitingForTheDeadline() = runTest {
        var polls = 0
        val pending = async { awaitAccessibleVpnNetwork({ polls++; 1L }, { false }) }
        runCurrent()
        pending.cancelAndJoin()
        val pollsAtStop = polls
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(pollsAtStop, polls)
    }

    @Test(expected = EngineException::class)
    fun revokedTunAbortsReadinessInsteadOfReturningAnEarlierCandidate() = runTest {
        var open = true
        val pending = async {
            awaitAccessibleVpnNetwork({ if (!open) throw EngineException(EngineFailure.START); 1L }, { false })
        }
        runCurrent()
        open = false
        advanceTimeBy(50)
        runCurrent()
        pending.await()
    }
}
