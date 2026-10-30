package com.foxhole.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileMutationTransactionTest {
    private data class Snapshot(val id: String = "p", val config: String = "v1", val name: String = "Owned profile")

    @Test
    fun anIdleMutationWritesAndPublishesTheSameSnapshot() = runTest {
        val before = Snapshot()
        val candidate = before.copy(config = "v2")
        var disk = before
        var published = before
        val result = commitProfileMutation(before, candidate, { transform -> transform(disk).also { disk = it } }, { true }) {
            published = it
            true
        }
        assertEquals(ProfileMutationResult.APPLIED, result)
        assertEquals(candidate, disk)
        assertEquals(disk, published)
    }

    @Test
    fun aRefusedOwnerNeverBeginsAWriteOrClaimsSuccess() = runTest {
        var writes = 0
        var publishes = 0
        val result = commitProfileMutation(1, 2, { transform -> writes++; transform(1) }, { false }) {
            publishes++
            true
        }
        assertEquals(ProfileMutationResult.SUPERSEDED, result)
        assertEquals(0, writes)
        assertEquals(0, publishes)
    }

    @Test
    fun aWidgetStartDuringTheDiskWriteRollsBackTheSameIdConfigRefresh() = runTest {
        val commands = ConnectionCommandFence()
        val before = Snapshot()
        val candidate = before.copy(config = "v2")
        val expected = owner(commands)
        var current = expected
        var disk = before
        val published = mutableListOf<Snapshot>()
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var result: ProfileMutationResult? = null
        val job = launch {
            result = commitProfileMutation(before, candidate, { transform ->
                disk = transform(disk)
                if (++writes == 1) { written.complete(Unit); release.await() }
                disk
            }, { profileMutationAdmitted(expected, current, true, null) }) {
                synchronized(commands) {
                    if (!profileMutationAdmitted(expected, current, true, null)) false
                    else { published += it; true }
                }
            }
        }
        runCurrent()
        written.await()
        val start = commands.reserveStart()
        current = owner(commands, VpnPhase.CONNECTING)
        release.complete(Unit)
        job.join()
        assertEquals(ProfileMutationResult.SUPERSEDED, result)
        assertEquals(before, disk)
        assertTrue(published.isEmpty())
        assertTrue(commands.isCurrent(start))
        assertTrue(commands.hasPendingStart())
        assertEquals(2, writes)
    }

    @Test
    fun aNewStopDuringTheWriteIsNeverReplacedByARefreshReconnect() = runTest {
        val commands = ConnectionCommandFence()
        val start = commands.reserveStart()
        commands.dispatch(start) {}
        val expected = owner(commands, VpnPhase.CONNECTED, true)
        var current = expected
        var disk = 1
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var reconnects = 0
        var result: ProfileMutationResult? = null
        val job = launch {
            result = commitProfileMutation(1, 2, { transform ->
                disk = transform(disk)
                if (++writes == 1) { written.complete(Unit); release.await() }
                disk
            }, { profileMutationAdmitted(expected, current, true, null) }) {
                if (!profileMutationAdmitted(expected, current, true, null)) false
                else { reconnects++; true }
            }
        }
        runCurrent()
        written.await()
        commands.stop {}
        current = owner(commands, VpnPhase.STOPPING, true)
        val stoppedGeneration = commands.currentGeneration()
        release.complete(Unit)
        job.join()
        assertEquals(ProfileMutationResult.SUPERSEDED, result)
        assertEquals(1, disk)
        assertEquals(0, reconnects)
        assertEquals(stoppedGeneration, commands.currentGeneration())
        assertFalse(commands.hasPendingStart())
    }

    @Test
    fun cancellingTheUiAfterDiskCommitStillPublishesBeforeTheTransactionSettles() = runTest {
        var disk = 1
        var published = 1
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            commitProfileMutation(1, 2, { transform ->
                disk = transform(disk)
                written.complete(Unit)
                release.await()
                disk
            }, { true }) { published = it; true }
        }
        runCurrent()
        written.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(2, disk)
        assertEquals(disk, published)
    }

    @Test
    fun cancellationBeforeCommitBeginsPropagatesWithoutWriting() = runTest {
        var writes = 0
        var publishes = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            commitProfileMutation(1, 2, { transform -> writes++; transform(1) }, { true }) { publishes++; true }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, writes)
        assertEquals(0, publishes)
    }

    @Test
    fun cancellationWhileTheStoreIsQueuedStillPreventsTheCommit() = runTest {
        var disk = 1
        var publishes = 0
        val queued = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            commitProfileMutation(1, 2, { transform ->
                queued.complete(Unit)
                release.await()
                disk = transform(disk)
                disk
            }, { true }) { publishes++; true }
        }
        runCurrent()
        queued.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, disk)
        assertEquals(0, publishes)
    }

    @Test
    fun cancellationCannotInterruptRollbackAfterANewerStop() = runTest {
        var disk = 1
        var published = 1
        var current = true
        var writes = 0
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            commitProfileMutation(1, 2, { transform ->
                disk = transform(disk)
                if (++writes == 1) { written.complete(Unit); release.await() }
                disk
            }, { current }) {
                if (current) { published = it; true } else false
            }
        }
        runCurrent()
        written.await()
        job.cancel()
        current = false
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, disk)
        assertEquals(disk, published)
        assertEquals(2, writes)
    }

    @Test
    fun metadataCanCommitDuringANewerStartWithoutChangingItsOwner() = runTest {
        val commands = ConnectionCommandFence()
        val expected = owner(commands)
        val before = Snapshot()
        val candidate = before.copy(name = "Renamed profile")
        var disk = before
        var published = before
        var current = expected
        var newerStart = -1L
        val result = commitProfileMutation(before, candidate, { transform ->
            disk = transform(disk)
            newerStart = commands.reserveStart()
            current = owner(commands, VpnPhase.CONNECTING)
            disk
        }, { profileMutationAdmitted(expected, current, false, null) }) {
            published = it
            true
        }
        assertEquals(ProfileMutationResult.APPLIED, result)
        assertEquals(candidate, disk)
        assertEquals(disk, published)
        assertTrue(commands.isCurrent(newerStart))
        assertTrue(commands.hasPendingStart())
        assertEquals(ProfileMutationAction.KEEP, profileMutationAction(expected, false, false, null))
    }

    @Test
    fun aStableConnectedRefreshReservesReplacementAndStoppingBeforePublication() = runTest {
        val commands = ConnectionCommandFence()
        commands.dispatch(commands.reserveStart()) {}
        val expected = owner(commands, VpnPhase.CONNECTED, true)
        var current = expected
        var disk = 1
        var published = 1
        var phaseAtPublication: VpnPhase? = null
        var replacement: Long? = null
        val result = commitProfileMutation(1, 2, { transform -> transform(disk).also { disk = it } }, {
            profileMutationAdmitted(expected, current, true, null)
        }) {
            synchronized(commands) {
                if (!profileMutationAdmitted(expected, current, true, null)) false
                else {
                    assertEquals(ProfileMutationAction.RECONNECT, profileMutationAction(expected, false, true, null))
                    replacement = commands.reserveStartIfCurrent(expected.generation)
                    current = owner(commands, VpnPhase.STOPPING, true)
                    phaseAtPublication = current.phase
                    published = it
                    true
                }
            }
        }
        assertEquals(ProfileMutationResult.APPLIED, result)
        assertEquals(2, disk)
        assertEquals(disk, published)
        assertEquals(VpnPhase.STOPPING, phaseAtPublication)
        assertTrue(commands.hasPendingStart())
        assertTrue(commands.isCurrent(requireNotNull(replacement)))
    }

    @Test
    fun pendingAndTransitionalOwnersRefuseSelectedConfigChanges() {
        listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.STOPPING).forEach { phase ->
            val snapshot = ProfileMutationOwner(1L, phase, true, false)
            assertFalse(profileMutationAdmitted(snapshot, snapshot, true, null))
        }
        val pending = ProfileMutationOwner(1L, VpnPhase.IDLE, false, true)
        assertFalse(profileMutationAdmitted(pending, pending, true, null))
        val retainedTunError = ProfileMutationOwner(1L, VpnPhase.ERROR, true, false)
        assertFalse(profileMutationAdmitted(retainedTunError, retainedTunError, true, null))
    }

    @Test
    fun anExplicitConnectedProfileSwitchKeepsItsExistingPendingTicket() {
        val expected = ProfileMutationOwner(7L, VpnPhase.STOPPING, true, true)
        val current = expected.copy(phase = VpnPhase.CONNECTING, hasOwnedTunnel = false)
        assertTrue(profileMutationAdmitted(expected, current, true, 7L))
        assertFalse(profileMutationAdmitted(expected, current.copy(generation = 8L), true, 7L))
        assertFalse(profileMutationAdmitted(expected, current.copy(hasPendingStart = false), true, 7L))
        assertEquals(ProfileMutationAction.STOP, profileMutationAction(expected, true, false, 7L))
    }

    @Test
    fun aRollbackWriteFailureBlocksTheStoreWithoutPublishingOverTheNewerOwner() = runTest {
        val commands = ConnectionCommandFence()
        var disk = 1
        var published = 1
        var writes = 0
        var blocked = false
        var newerStart = -1L
        try {
            commitProfileMutation(1, 2, { transform ->
                if (++writes == 2) throw java.io.IOException("Owned write failure")
                disk = transform(disk)
                newerStart = commands.reserveStart()
                disk
            }, { true }, onRollbackFailure = { blocked = true }) {
                false
            }
            fail("Rollback failure must be reported")
        } catch (_: java.io.IOException) {
            assertTrue(blocked)
        }
        assertEquals(2, disk)
        assertEquals(1, published)
        assertTrue(commands.isCurrent(newerStart))
        assertTrue(commands.hasPendingStart())
        assertEquals(2, writes)
    }

    @Test
    fun rollbackCancellationRecordsTheFaultAndPreservesCancellationCause() = runTest {
        val cancelled = CancellationException("Owned store cancellation")
        var writes = 0
        var disk = 1
        var blocked = false
        try {
            commitProfileMutation(1, 2, { transform ->
                if (++writes == 2) throw cancelled
                disk = transform(disk)
                disk
            }, { true }, onRollbackFailure = { blocked = true }) { false }
            fail("Rollback cancellation must propagate")
        } catch (failure: CancellationException) {
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.take(8).any { it === cancelled })
        }
        assertTrue(blocked)
        assertEquals(2, disk)
        assertEquals(2, writes)
    }

    @Test
    fun changingTheSelectedIdWhileConnectedStopsInsteadOfSilentlyConnectingAnotherProfile() {
        val connected = ProfileMutationOwner(1L, VpnPhase.CONNECTED, true, false)
        assertEquals(ProfileMutationAction.STOP, profileMutationAction(connected, true, true, null))
        assertEquals(ProfileMutationAction.KEEP, profileMutationAction(connected, false, false, null))
        assertEquals(ProfileMutationAction.KEEP, profileMutationAction(connected.copy(phase = VpnPhase.IDLE), true, true, null))
    }

    private fun owner(commands: ConnectionCommandFence, phase: VpnPhase = VpnPhase.IDLE, owned: Boolean = false) =
        ProfileMutationOwner(commands.currentGeneration(), phase, owned, commands.hasPendingStart())
}
