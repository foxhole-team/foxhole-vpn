package com.foxhole.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DnsContinuationLeaseTest {
    @Test fun successfulStartReleasesOnceAfterItSettles() {
        val calls = mutableListOf<Long>()
        val lease = DnsContinuationLease(calls::add)
        lease.captured(11)
        lease.oldStoppedCleanly()
        val use = lease.beginStart()
        assertEquals(11, use.token)
        assertTrue(calls.isEmpty())
        use.finished(); use.finished(); lease.close()
        assertEquals(listOf(11L), calls)
    }

    @Test fun captureFinishingAfterStopReleasesWithoutImport() {
        val calls = mutableListOf<Long>()
        val lease = DnsContinuationLease(calls::add)
        lease.close(); lease.close(); lease.captured(12); lease.captured(12)
        assertEquals(listOf(12L), calls)
        expectFailure { lease.oldStoppedCleanly() }
        expectFailure { lease.beginStart() }
    }

    @Test fun missingCleanStopFenceNeverImports() {
        val calls = mutableListOf<Long>()
        val lease = DnsContinuationLease(calls::add)
        lease.captured(13)
        expectFailure { lease.beginStart() }
        lease.close()
        assertEquals(listOf(13L), calls)
    }

    @Test fun cancelledQueuedStartDoesNotBorrowReleasedToken() {
        val calls = mutableListOf<Long>()
        val lease = DnsContinuationLease(calls::add)
        lease.captured(14); lease.oldStoppedCleanly(); lease.close()
        expectFailure { lease.beginStart() }
        assertEquals(listOf(14L), calls)
    }

    @Test fun stopDuringNativeStartDefersReleaseUntilLateOwnerStop() {
        val calls = mutableListOf<String>()
        val lease = DnsContinuationLease { calls += "release:$it" }
        lease.captured(15); lease.oldStoppedCleanly()
        val use = lease.beginStart()
        lease.close()
        assertTrue(calls.isEmpty())
        calls += "late-owner-stop"
        use.finished(); lease.close()
        assertEquals(listOf("late-owner-stop", "release:15"), calls)
    }

    @Test fun onlyOneStartCanBorrowTheCapturedToken() {
        val calls = mutableListOf<Long>()
        val lease = DnsContinuationLease(calls::add)
        lease.captured(16); lease.oldStoppedCleanly()
        val use = lease.beginStart()
        expectFailure { lease.beginStart() }
        use.finished()
        expectFailure { lease.beginStart() }
        assertEquals(listOf(16L), calls)
    }

    @Test fun captureAndStopRaceStillReleaseExactlyOnce() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val lease = DnsContinuationLease(calls::add)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val capture = workers.submit { start.await(); lease.captured(17) }
            val close = workers.submit { start.await(); lease.close() }
            start.countDown()
            capture.get(2, TimeUnit.SECONDS); close.get(2, TimeUnit.SECONDS)
            lease.close()
            assertEquals(listOf(17L), calls)
        } finally { workers.shutdownNow() }
    }

    @Test fun rejectedCaptureHasNoPositiveTokenToRelease() {
        val calls = mutableListOf<Long>()
        val lease = DnsContinuationLease(calls::add)
        for (token in listOf(0L, -1L, -2L)) expectFailure { lease.captured(token) }
        lease.close()
        assertTrue(calls.isEmpty())
    }

    @Test fun releaseFailureIsNotRetriedAsADuplicateNativeControl() {
        var attempts = 0
        val lease = DnsContinuationLease { attempts++; throw IllegalStateException("release failed") }
        lease.captured(18)
        expectFailure { lease.close() }
        lease.close()
        assertEquals(1, attempts)
    }

    @Test fun continuationRequiresBothAnExactWgProtocolAndManagedPacketDns() {
        val managed = """{"dns":{"packet_resolvers":["10.0.0.1"]}}"""
        assertTrue(managedWireGuardPacketDns(setOf("wireguard"), managed))
        assertFalse(managedWireGuardPacketDns(setOf("vless"), managed))
        assertFalse(managedWireGuardPacketDns(setOf("wireguard", "vless"), managed))
        assertFalse(managedWireGuardPacketDns(setOf("wireguard"), """{"dns":{}}"""))
        assertFalse(managedWireGuardPacketDns(setOf("wireguard"), """{"dns":{"packet_resolvers":[]}}"""))
    }

    @Test fun unpublishedQuarantinedAndFailedStartsRemainUnsettled() {
        assertTrue(dnsContinuationStartUnsettled(-1))
        assertTrue(dnsContinuationStartUnsettled(-2))
        assertFalse(dnsContinuationStartUnsettled(0))
        assertFalse(dnsContinuationStartUnsettled(31))
    }

    @Test fun aFailedReleaseCannotPublishASettledTarget() {
        requireDnsContinuationSettled(true)
        var failure: EngineException? = null
        try { requireDnsContinuationSettled(false) } catch (error: EngineException) { failure = error }
        assertEquals(EngineFailure.START, failure?.reason)
        assertTrue(failure?.processPoisoned == true)
    }

    @Test fun onlyKnownCleanStopCodesCanQualifyTheGracefulFence() {
        assertTrue(dnsContinuationStopClean(0)); assertTrue(dnsContinuationStopClean(1))
        for (code in listOf(null, -1, 2, 3)) assertFalse(dnsContinuationStopClean(code))
    }

    private fun expectFailure(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: IllegalStateException) { failed = true }
        catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
