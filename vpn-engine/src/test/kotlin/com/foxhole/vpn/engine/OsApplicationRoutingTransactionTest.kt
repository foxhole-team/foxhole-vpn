package com.foxhole.vpn.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OsApplicationRoutingTransactionTest {
    @Test
    fun barrierPrecedesOldCloseAndPersistenceWhileStopIsSuspended() = runTest {
        val fixture = Fixture().apply { pauseAt = "stop" }
        val task = async { fixture.transaction.apply(fixture::persist) }
        fixture.entered.await()
        assertEquals(listOf("hold", "gate:1", "stop"), fixture.calls)
        assertTrue(fixture.barrierActive)
        assertFalse(fixture.persisted)
        assertFalse(fixture.targetActive)
        fixture.resume.complete(Unit)
        runCurrent()
        assertEquals(location, task.await())
        assertEquals(listOf("hold", "gate:1", "stop", "persist", "target", "release"), fixture.calls)
        assertTrue(fixture.persisted)
        assertTrue(fixture.targetActive)
        assertFalse(fixture.barrierActive)
    }

    @Test
    fun unavailableInitialAndRecoveryBarriersNeverCloseOldOrWriteStore() = runTest {
        val fixture = Fixture().apply { failedBarriers = setOf(1, 2) }
        assertTrue(runCatching { fixture.transaction.apply(fixture::persist) }.isFailure)
        assertEquals(listOf("hold", "gate:1", "gate:2"), fixture.calls)
        assertFalse(fixture.oldClosed)
        assertFalse(fixture.persisted)
        assertFalse(fixture.targetActive)
        assertFalse(fixture.retired)
    }

    @Test
    fun failedPersistenceRetainsBarrierAndNeverEstablishesTarget() = runTest {
        val failure = IllegalStateException("secure store rejected")
        val fixture = Fixture().apply { persistFailure = failure }
        val result = runCatching { fixture.transaction.apply(fixture::persist) }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf("hold", "gate:1", "stop", "persist", "gate:2", "retire"), fixture.calls)
        assertTrue(fixture.barrierActive)
        assertTrue(fixture.oldClosed)
        assertFalse(fixture.persisted)
        assertFalse(fixture.targetEverEstablished)
    }

    @Test
    fun failedNativeStartOrProofReestablishesBarrierBeforeTargetClose() = runTest {
        for (reason in listOf("native startup rejected", "VPN-bound proof failed")) {
            val failure = IllegalStateException(reason)
            val fixture = Fixture().apply { releaseFailure = failure }
            val result = runCatching { fixture.transaction.apply(fixture::persist) }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(listOf("hold", "gate:1", "stop", "persist", "target", "release", "gate:2", "retire"), fixture.calls)
            assertTrue(fixture.targetEverEstablished)
            assertTrue(fixture.barrierActive)
            assertFalse(fixture.targetActive)
            assertTrue(fixture.retired)
        }
    }

    @Test
    fun failedRecoveryDoesNotMistakeRetainedOldGateForAnActiveBarrier() = runTest {
        val fixture = Fixture().apply {
            releaseFailure = IllegalStateException("proof failed")
            failedBarriers = setOf(2)
        }
        assertTrue(runCatching { fixture.transaction.apply(fixture::persist) }.isFailure)
        assertEquals(listOf("hold", "gate:1", "stop", "persist", "target", "release", "gate:2"), fixture.calls)
        assertTrue(fixture.targetActive)
        assertFalse(fixture.barrierActive)
        assertFalse(fixture.retired)
    }

    @Test
    fun supersededOwnerCannotContinueAfterAnySuspendedStageOrReportSuccess() = runTest {
        val stages = listOf("hold", "gate", "stop", "persist", "target", "release")
        for ((index, stage) in stages.withIndex()) {
            val fixture = Fixture().apply { pauseAt = stage }
            val task = async { runCatching { fixture.transaction.apply(fixture::persist) } }
            fixture.entered.await()
            fixture.current = false
            fixture.resume.complete(Unit)
            assertTrue("late $stage completion must not succeed", task.await().isFailure)
            assertEquals(stages.take(index + 1), fixture.calls.map { it.substringBefore(':') })
            assertFalse(fixture.retired)
        }
    }

    // This models only callback effects and OS gate deactivation. It calls the
    // production transaction; native FD cleanup and secure-store fencing are
    // independently owned by the driver/controller rather than this seam.
    private class Fixture {
        val calls = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var current = true
        var pauseAt: String? = null
        var failedBarriers = emptySet<Int>()
        var persistFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        var barrierActive = false
        var targetActive = false
        var targetEverEstablished = false
        var oldClosed = false
        var persisted = false
        var retired = false
        private var barriers = 0

        val transaction = OsApplicationRoutingTransaction(
            isCurrent = { current },
            holdNative = { checkpoint("hold") },
            establishBarrier = {
                val number = ++barriers
                checkpoint("gate:$number")
                check(number !in failedBarriers) { "barrier unavailable" }
                barrierActive = true
            },
            stopPrevious = {
                assertTrue("old close requires an active full barrier", barrierActive)
                checkpoint("stop")
                oldClosed = true
            },
            establishTarget = {
                assertTrue("target scope must follow persistence", persisted)
                checkpoint("target")
                barrierActive = false
                targetActive = true
                targetEverEstablished = true
            },
            releaseAndValidate = {
                checkpoint("release")
                releaseFailure?.let { throw it }
                location
            },
            retireTarget = {
                assertTrue("target close requires a newly active barrier", barrierActive)
                checkpoint("retire")
                targetActive = false
                retired = true
            },
        )

        suspend fun persist() {
            assertTrue("store write requires the full barrier", barrierActive)
            assertTrue(oldClosed)
            checkpoint("persist")
            persistFailure?.let { throw it }
            persisted = true
        }

        private suspend fun checkpoint(stage: String) {
            calls += stage
            if (pauseAt == stage.substringBefore(':')) {
                entered.complete(Unit)
                resume.await()
            }
        }
    }

    private companion object {
        val location = EngineLocation("US", "Example", "United States", "203.0.113.10")
    }
}
