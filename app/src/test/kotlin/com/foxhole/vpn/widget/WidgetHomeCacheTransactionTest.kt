package com.foxhole.vpn.widget

import com.foxhole.vpn.ConnectionCommandFence
import com.foxhole.vpn.ProfileMutationResult
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.commitProfileMutation
import com.foxhole.vpn.storage.ProfileStoreState
import com.foxhole.vpn.storage.StoredDevicePlace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetHomeCacheTransactionTest {
    @Test
    fun aStartDuringCacheWriteRollsBackOnlyCacheAndCannotPublishHome() = runTest {
        val commands = ConnectionCommandFence()
        val generation = commands.currentGeneration()
        val old = StoredDevicePlace("DE", "Old", "Germany")
        val new = old.copy(city = "New")
        val before = ProfileStoreState(selectedProfileId = "Preserved selection",
            settings = VpnSettings(theme = VpnTheme.DARK), lastKnownDevicePlace = old)
        var disk = before
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var published = false
        var result: ProfileMutationResult? = null
        val request = launch {
            result = commitProfileMutation<StoredDevicePlace?>(old, new, { transform ->
                disk = disk.copy(lastKnownDevicePlace = transform(disk.lastKnownDevicePlace))
                if (++writes == 1) { written.complete(Unit); release.await() }
                disk.lastKnownDevicePlace
            }, { commands.isCurrentWithoutPendingStart(generation) }) {
                published = commands.isCurrentWithoutPendingStart(generation)
                published
            }
        }
        runCurrent()
        written.await()
        val start = commands.reserveStart()
        release.complete(Unit)
        request.join()
        assertEquals(ProfileMutationResult.SUPERSEDED, result)
        assertEquals(before, disk)
        assertFalse(published)
        assertTrue(commands.isCurrent(start))
        assertTrue(commands.hasPendingStart())
        assertEquals(2, writes)
    }

    @Test
    fun cancelledWorkerDuringCacheWriteRollsBackAndDoesNotPublishFreshData() = runTest {
        val old = StoredDevicePlace("DE", "Old", "Germany")
        var disk: StoredDevicePlace? = old
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var published = false
        val request = launch {
            val caller = currentCoroutineContext()
            commitProfileMutation(disk, old.copy(city = "New"), { transform ->
                disk = transform(disk)
                if (++writes == 1) { written.complete(Unit); release.await() }
                disk
            }, { caller.isActive }) {
                published = caller.isActive
                published
            }
        }
        runCurrent()
        written.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertEquals(old, disk)
        assertFalse(published)
        assertEquals(2, writes)
    }
}
