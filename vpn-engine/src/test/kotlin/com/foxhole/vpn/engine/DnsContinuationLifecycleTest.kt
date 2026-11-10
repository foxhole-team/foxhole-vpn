package com.foxhole.vpn.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production transaction + lease seam; no Android, JNI or traffic proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsContinuationLifecycleTest {
    @Test fun captureFollowsHoldAndBarrierAndImportFollowsCleanStop() = runTest {
        val f = Fixture()
        f.apply()
        assertEquals(listOf("hold", "barrier", "capture", "stop", "persist", "start", "release-token:41", "validate"), f.calls)
        assertTrue(f.stopped)
        assertTrue(f.started)
    }

    @Test fun failedBarrierNeverCapturesOrStops() = runTest {
        val f = Fixture().apply { barrierFailure = true }
        try { f.apply(); error("failure expected") } catch (_: IllegalStateException) { }
        assertFalse("capture" in f.calls)
        assertFalse(f.stopped)
        assertFalse(f.started)
    }

    @Test fun failedCleanStopNeverImportsAndReleasesCapturedToken() = runTest {
        val f = Fixture().apply { cleanStop = false }
        try { f.apply(); error("failure expected") } catch (_: IllegalStateException) { }
        assertFalse(f.started)
        assertEquals(1, f.calls.count { it == "release-token:41" })
        assertTrue(f.calls.indexOf("stop") < f.calls.indexOf("release-token:41"))
    }

    @Test fun failedPersistenceReleasesWithoutStartingTarget() = runTest {
        val f = Fixture().apply { persistenceFailure = true }
        try { f.apply(); error("failure expected") } catch (_: IllegalStateException) { }
        assertFalse(f.started)
        assertEquals(1, f.calls.count { it == "release-token:41" })
    }

    @Test fun stopWhileCaptureIsLateReleasesBeforeAnyImport() = runTest {
        val f = Fixture().apply { pauseCapture = true }
        val task = async {
            try { f.apply() } catch (_: IllegalStateException) { }
        }
        f.entered.await()
        f.current = false
        f.lease.close()
        f.resume.complete(Unit)
        task.await()
        assertFalse(f.stopped)
        assertFalse(f.started)
        assertEquals(1, f.calls.count { it == "release-token:41" })
    }

    @Test fun cancelledNativeStartKeepsLeaseUntilExistingLateOwnerCleanup() = runTest {
        val f = Fixture().apply { pauseStart = true }
        val task = async { f.apply() }
        f.entered.await()
        f.current = false
        task.cancelAndJoin()
        assertFalse(f.calls.any { it.startsWith("release-token:") })
        f.lateSuccess(73)
        assertEquals(listOf("late-stop:73", "release-token:41"), f.calls.takeLast(2))
        assertFalse("validate" in f.calls)
    }

    @Test fun quarantinedOrFailedStartIsUnsettledWithoutPlainFallback() = runTest {
        for (result in listOf(-1L, -2L)) {
            val f = Fixture().apply { startResult = result }
            try { f.apply(); error("failure expected") } catch (_: IllegalStateException) { }
            assertFalse(f.nativeSettled)
            assertFalse("plain-start" in f.calls)
            assertFalse("validate" in f.calls)
            assertEquals(1, f.calls.count { it == "release-token:41" })
        }
    }

    @Test fun failedTokenReleaseRetiresTargetBeforeAnyUnblockOrValidation() = runTest {
        val f = Fixture().apply { tokenReleaseFailure = true }
        try { f.apply(); error("failure expected") } catch (failure: EngineException) {
            assertTrue(failure.processPoisoned)
        }
        assertFalse("validate" in f.calls)
        assertEquals("retire", f.calls.last())
        assertEquals(1, f.calls.count { it == "release-token:41" })
    }

    private class Fixture {
        val calls = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val lease = DnsContinuationLease { calls += "release-token:$it"; if (tokenReleaseFailure) nativeSettled = false }
        var current = true
        var barrierFailure = false
        var cleanStop = true
        var persistenceFailure = false
        var pauseCapture = false
        var pauseStart = false
        var stopped = false
        var started = false
        var nativeSettled = true
        var tokenReleaseFailure = false
        var startResult = 73L
        private var borrowed: DnsContinuationLease.Start? = null
        private val transaction = OsApplicationRoutingTransaction(
            isCurrent = { current },
            holdNative = { calls += "hold" },
            establishBarrier = { calls += "barrier"; check(!barrierFailure) },
            stopPrevious = {
                calls += "capture"
                if (pauseCapture) { entered.complete(Unit); resume.await() }
                lease.captured(41)
                check(current)
                calls += "stop"
                check(cleanStop)
                stopped = true
                lease.oldStoppedCleanly()
            },
            establishTarget = {
                check(stopped)
                calls += "start"
                borrowed = lease.beginStart()
                started = true
                if (pauseStart) { entered.complete(Unit); resume.await() }
                borrowed?.finished()
                requireDnsContinuationSettled(nativeSettled)
                if (dnsContinuationStartUnsettled(startResult)) {
                    nativeSettled = false
                    error("unpublished cleanup unsettled")
                }
            },
            releaseAndValidate = { calls += "validate"; null },
            retireTarget = { calls += "retire" },
        )

        suspend fun apply() {
            try {
                transaction.apply { calls += "persist"; check(!persistenceFailure) }
            } finally { lease.close() }
        }

        fun lateSuccess(handle: Long) {
            calls += "late-stop:$handle"
            borrowed?.finished()
            lease.close()
        }
    }
}
