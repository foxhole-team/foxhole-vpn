package com.foxhole.vpn.storage

import com.foxhole.vpn.VpnLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePlaceCacheWriterTest {
    private val location = VpnLocation("DE", "CacheCityFixture", "CacheCountryFixture", "192.0.2.12", 52.5, 13.4)

    @Test
    fun onlyLatestConfirmedHomeIsSavedAfterHydration() = runTest {
        val ready = CompletableDeferred<Unit>()
        val saved = mutableListOf<StoredDevicePlace>()
        val writer = DevicePlaceCacheWriter(backgroundScope, { ready.await() }, { saved += it })
        writer.confirmedHome(location)
        writer.confirmedHome(location.copy(city = "New city"))
        runCurrent()
        assertEquals(emptyList<StoredDevicePlace>(), saved)
        ready.complete(Unit)
        runCurrent()
        assertEquals(listOf(storedDevicePlace(location.copy(city = "New city"))), saved)
    }

    @Test
    fun unchangedDisplayPlaceDoesNotWriteAgainForIpOrTimestampRefreshes() = runTest {
        val saved = mutableListOf<StoredDevicePlace>()
        val writer = DevicePlaceCacheWriter(backgroundScope, {}, { saved += it })
        writer.confirmedHome(location)
        runCurrent()
        writer.confirmedHome(location.copy(ip = "192.0.2.13"))
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(listOf(storedDevicePlace(location)), saved)
    }

    @Test
    fun optionalCacheFailureDoesNotRetryOrDiscardAFutureDifferentHome() = runTest {
        var attempts = 0
        val saved = mutableListOf<StoredDevicePlace>()
        val writer = DevicePlaceCacheWriter(backgroundScope, {}) {
            attempts++
            if (attempts == 1) error("cache_write_fixture")
            saved += it
        }
        writer.confirmedHome(location)
        runCurrent()
        writer.confirmedHome(location.copy(ip = "192.0.2.13"))
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, attempts)
        writer.confirmedHome(location.copy(city = "New city"))
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(listOf(storedDevicePlace(location.copy(city = "New city"))), saved)
    }

    @Test
    fun cancellationDoesNotTurnIntoABackgroundRetry() = runTest {
        var attempts = 0
        val writer = DevicePlaceCacheWriter(backgroundScope, {}) {
            attempts++
            throw CancellationException("cache_cancel_fixture")
        }
        writer.confirmedHome(location)
        runCurrent()
        writer.confirmedHome(location.copy(city = "New city"))
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, attempts)
    }
}
