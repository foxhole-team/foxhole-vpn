package com.foxhole.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileChoiceTransactionTest {
    @Test fun staleAdmissionDoesNotBeginIoOrPublication() = runTest {
        var writes = 0
        var publications = 0
        commitFencedProfileChoice(1, 2, { transform -> writes++; transform(1) }, { false }) { publications++; true }
        assertEquals(0, writes)
        assertEquals(0, publications)
    }

    @Test fun aConfirmedChoiceReservesReconnectOnlyAfterTheStoreWriteWinsTheFence() = runTest {
        val commands = ConnectionCommandFence()
        val owner = commands.reserveStart()
        commands.dispatch(owner) {}
        var stored = 1
        val events = mutableListOf<String>()
        var ticket: Long? = null
        commitFencedProfileChoice(1, 2, { transform -> events += "write"; transform(stored).also { stored = it } },
            { commands.isCurrentWithoutPendingStart(owner) }) {
            synchronized(commands) {
                ticket = commands.reserveStartIfCurrent(owner)
                if (ticket == null) false else { events += listOf("reserve", "hide-geo", "publish", "start"); true }
            }
        }
        assertEquals(2, stored)
        assertEquals(listOf("write", "reserve", "hide-geo", "publish", "start"), events)
        assertTrue(commands.hasPendingStart())
        assertTrue(commands.isCurrent(requireNotNull(ticket)))
        assertFalse(commands.isCurrent(owner))
    }

    @Test fun aStopDuringConfirmedChoiceWriteRollsBackAndNeverRestarts() = runTest {
        val commands = ConnectionCommandFence()
        val owner = commands.reserveStart()
        commands.dispatch(owner) {}
        var stored = 1
        var starts = 0
        var writes = 0
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            commitFencedProfileChoice(1, 2, { transform ->
                stored = transform(stored)
                if (++writes == 1) { written.complete(Unit); release.await() }
                stored
            }, { commands.isCurrentWithoutPendingStart(owner) }) {
                if (!commands.isCurrentWithoutPendingStart(owner)) false else { starts++; true }
            }
        }
        runCurrent()
        written.await()
        commands.stop {}
        val stopped = commands.currentGeneration()
        release.complete(Unit)
        job.join()
        assertEquals(1, stored)
        assertEquals(0, starts)
        assertTrue(commands.isCurrent(stopped))
        assertFalse(commands.hasPendingStart())
    }

    @Test fun anIdleChoiceCommitsAndPublishesTogether() = runTest {
        var stored = 1
        val published = mutableListOf<Int>()
        commitFencedProfileChoice(1, 2, { transform -> transform(stored).also { stored = it } }, { true }, publishIfCurrent = published::add)
        assertEquals(2, stored)
        assertEquals(listOf(2), published)
    }

    @Test fun aNewStartDuringTheWriteKeepsThePreviousChoice() = runTest {
        var stored = 1
        var current = true
        val published = mutableListOf<Int>()
        commitFencedProfileChoice(1, 2, { transform ->
            transform(stored).also { stored = it; current = false }
        }, { current }, publishIfCurrent = { value -> if (current) published.add(value) else false })
        assertEquals(1, stored)
        assertTrue(published.isEmpty())
    }

    @Test fun aNewStartAtTheFinalPublicationBoundaryRollsBackTheWrite() = runTest {
        var stored = 1
        val commands = ConnectionCommandFence()
        val generation = commands.currentGeneration()
        val published = mutableListOf<Int>()
        commitFencedProfileChoice(1, 2, { transform -> transform(stored).also { stored = it } }, { commands.isCurrent(generation) }) { value ->
            commands.reserveStart()
            synchronized(commands) {
                if (!commands.isCurrent(generation)) false else { published.add(value); true }
            }
        }
        assertEquals(1, stored)
        assertTrue(published.isEmpty())
        assertTrue(commands.hasPendingStart())
    }

    @Test fun closingTheUiCannotLeaveACommittedStoreUnpublished() = runTest {
        var stored = 1
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        val job = launch {
            commitFencedProfileChoice(1, 2, { transform ->
                stored = transform(stored)
                written.complete(Unit)
                release.await()
                stored
            }, { true }, publishIfCurrent = published::add)
        }
        runCurrent()
        written.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(2, stored)
        assertEquals(listOf(2), published)
    }

    @Test fun aFailedRollbackRecordsTheFaultWithoutPublishingTheCandidate() = runTest {
        var stored = 1
        var writes = 0
        var fault = false
        var failed = false
        var publicationAttempts = 0
        try {
            commitFencedProfileChoice(1, 2, { transform ->
                if (++writes == 2) throw IOException("Owned rollback failure")
                transform(stored).also { stored = it }
            }, { true }, rollbackFailed = { fault = true }, publishIfCurrent = { publicationAttempts++; false })
        } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertTrue(fault)
        assertEquals(1, publicationAttempts)
        assertEquals(2, stored)
    }
}
