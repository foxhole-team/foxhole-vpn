package com.foxhole.vpn

import com.foxhole.vpn.refresh.RefreshOutcome
import com.foxhole.vpn.refresh.attempted
import com.foxhole.vpn.storage.ProfileStoreState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticLedgerTransactionTest {
    @Test fun acceptedCheckLedgerWriteSettlesPublicationWhenTheCallerIsCancelled() = runTest {
        val before = ProfileStoreState()
        val candidate = before.copy(
            automaticRefresh = before.automaticRefresh.copy(
                connectionData = before.automaticRefresh.connectionData.attempted(1_000, RefreshOutcome.UNCHANGED),
            )
        )
        var disk = before
        var published = before
        lateinit var caller: Job
        caller = launch(start = CoroutineStart.LAZY) {
            commitProfileMutation(before, candidate, { transform ->
                transform(disk).also {
                    disk = it
                    caller.cancel()
                }
            }, { true }) {
                published = it
                true
            }
        }
        caller.start()
        runCurrent()
        assertEquals(candidate, disk)
        assertEquals(disk, published)
    }

    @Test fun aSupersededWriteRollsBackTheSuccessTimestampTogetherWithItsData() = runTest {
        val before = ProfileStoreState()
        val candidate = before.copy(
            automaticRefresh = before.automaticRefresh.copy(
                connectionData = before.automaticRefresh.connectionData.attempted(1_000, RefreshOutcome.AVAILABLE),
            )
        )
        var disk = before
        val result = commitProfileMutation(
            before,
            candidate,
            { transform -> transform(disk).also { disk = it } },
            { true }
        ) { false }
        assertEquals(ProfileMutationResult.SUPERSEDED, result)
        assertEquals(before, disk)
        assertEquals(0L, disk.automaticRefresh.connectionData.lastSuccessAtMillis)
    }
}
