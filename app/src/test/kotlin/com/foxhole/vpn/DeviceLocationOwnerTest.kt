package com.foxhole.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLocationOwnerTest {
    @Test
    fun backgroundAndTunnelStatesDoNotRequestDeviceGeography() = runTest {
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, { requests++; location }, { 42L })
        owner.networkChanged("network-a")
        runCurrent()
        assertEquals(0, requests)
        owner.setTunnelActive(true)
        owner.setForeground(true)
        runCurrent()
        assertEquals(0, requests)
        assertEquals(DeviceLocationStatus.UNKNOWN, owner.state.value.status)
    }

    @Test
    fun foregroundFetchHasNoPollAndIgnoresDuplicateNetworkCallbacks() = runTest {
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, { requests++; location }, { 42L })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        assertEquals(VpnDeviceLocation(location, DeviceLocationStatus.CURRENT, 42L), owner.state.value)
        owner.networkChanged("network-a")
        owner.setForeground(true)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun scheduledRefreshUsesTheSameForegroundAndTunnelFence() = runTest {
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, { requests++; location }, { 42L })
        owner.networkChanged("network-a")
        owner.refreshNow()
        runCurrent()
        assertEquals(0, requests)
        owner.setForeground(true)
        runCurrent()
        owner.refreshNow()
        runCurrent()
        assertEquals(2, requests)
        owner.setTunnelActive(true)
        owner.refreshNow()
        runCurrent()
        assertEquals(2, requests)
        owner.setForeground(false)
        owner.setTunnelActive(false)
        owner.refreshNow()
        runCurrent()
        assertEquals(2, requests)
    }

    @Test
    fun connectKeepsPreviousDevicePlaceAndDisconnectRefreshesIt() = runTest {
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, { requests++; location.copy(city = "Place $requests") }, { 42L })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        owner.setTunnelActive(true)
        owner.networkChanged("owned-vpn")
        runCurrent()
        assertEquals(DeviceLocationStatus.CACHED, owner.state.value.status)
        assertEquals("Place 1", owner.state.value.location?.city)
        assertEquals(1, requests)
        owner.networkChanged("network-b")
        owner.setTunnelActive(false)
        runCurrent()
        assertEquals(DeviceLocationStatus.CURRENT, owner.state.value.status)
        assertEquals("Place 2", owner.state.value.location?.city)
        assertEquals(2, requests)
    }

    @Test
    fun lateCancelledNetworkResultCannotReplaceTheCurrentNetwork() = runTest {
        val old = CompletableDeferred<VpnLocation>()
        val owner = DeviceLocationOwner<String>(backgroundScope, { network ->
            if (network == "old") withContext(NonCancellable) { old.await() } else location
        }, { 42L })
        owner.networkChanged("old")
        owner.setForeground(true)
        runCurrent()
        owner.networkChanged("new")
        runCurrent()
        assertEquals(location, owner.state.value.location)
        old.complete(location.copy(city = "Old place"))
        runCurrent()
        assertEquals(location, owner.state.value.location)
        assertEquals(DeviceLocationStatus.CURRENT, owner.state.value.status)
    }

    @Test
    fun foregroundExitCancelsLookupAndDoesNotAcceptItsLateResult() = runTest {
        val deferred = CompletableDeferred<VpnLocation>()
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, {
            requests++
            withContext(NonCancellable) { deferred.await() }
        }, { 42L })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        owner.setForeground(false)
        runCurrent()
        deferred.complete(location)
        runCurrent()
        assertEquals(null, owner.state.value.location)
        assertFalse(owner.state.value.status == DeviceLocationStatus.CURRENT)
        assertEquals(1, requests)
    }

    @Test
    fun failedRefreshKeepsTimestampAndMarksCachedPlaceUnavailable() = runTest {
        var fail = false
        val owner = DeviceLocationOwner<String>(backgroundScope, {
            if (fail) error("unavailable") else location
        }, { 42L })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        fail = true
        owner.networkChanged("network-b")
        runCurrent()
        assertEquals(location, owner.state.value.location)
        assertEquals(DeviceLocationStatus.UNAVAILABLE, owner.state.value.status)
        assertEquals(42L, owner.state.value.checkedAtElapsedRealtimeMs)
    }

    @Test
    fun nextForegroundChecksAgainWithoutAnyBackgroundRequests() = runTest {
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, { requests++; location }, { 42L })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        owner.setForeground(false)
        owner.networkChanged("network-b")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(DeviceLocationStatus.CACHED, owner.state.value.status)
        owner.setForeground(true)
        runCurrent()
        assertEquals(2, requests)
    }

    @Test
    fun restoringLastKnownHomeDoesNotStartLookupOrClaimCurrentLocation() = runTest {
        var requests = 0
        val owner = DeviceLocationOwner<String>(backgroundScope, { requests++; location }, { 42L })
        owner.restoreCached(location.copy(ip = "", latitude = null, longitude = null))
        owner.networkChanged("network-a")
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(0, requests)
        assertEquals(DeviceLocationStatus.CACHED, owner.state.value.status)
        assertEquals("Example", owner.state.value.location?.city)
        assertEquals(null, owner.state.value.checkedAtElapsedRealtimeMs)
    }

    @Test
    fun restorationCannotReplaceFreshDataOrAnOngoingLookup() = runTest {
        val lookup = CompletableDeferred<VpnLocation>()
        val owner = DeviceLocationOwner<String>(backgroundScope, { lookup.await() }, { 42L })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        owner.restoreCached(location.copy(city = "Old city"))
        runCurrent()
        assertEquals(DeviceLocationStatus.LOADING, owner.state.value.status)
        assertEquals(null, owner.state.value.location)
        lookup.complete(location)
        runCurrent()
        owner.restoreCached(location.copy(city = "Old city"))
        runCurrent()
        assertEquals(VpnDeviceLocation(location, DeviceLocationStatus.CURRENT, 42L), owner.state.value)
    }

    @Test
    fun confirmedHomeCallbackIsNotLostToImmediateTunnelCaching() = runTest {
        val confirmed = mutableListOf<VpnLocation>()
        val owner = DeviceLocationOwner<String>(backgroundScope, { location }, { 42L }, { confirmed += it })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        owner.setTunnelActive(true)
        runCurrent()
        assertEquals(DeviceLocationStatus.CACHED, owner.state.value.status)
        assertEquals(listOf(location), confirmed)
        owner.networkChanged("vpn-network")
        owner.refreshNow()
        runCurrent()
        assertEquals(listOf(location), confirmed)
    }

    @Test
    fun aLateCancelledHomeLookupCannotPersistVpnOrOldNetworkGeography() = runTest {
        val deferred = CompletableDeferred<VpnLocation>()
        val confirmed = mutableListOf<VpnLocation>()
        val owner = DeviceLocationOwner<String>(backgroundScope, {
            withContext(NonCancellable) { deferred.await() }
        }, { 42L }, { confirmed += it })
        owner.networkChanged("network-a")
        owner.setForeground(true)
        runCurrent()
        owner.setTunnelActive(true)
        runCurrent()
        deferred.complete(location)
        runCurrent()
        assertTrue(confirmed.isEmpty())
        assertEquals(null, owner.state.value.location)
    }

    @Test
    fun confirmedWidgetHomeIsCurrentWithoutStartingABackgroundLookupOrRepeatingTheCacheWrite() = runTest {
        var lookups = 0
        val saved = mutableListOf<VpnLocation>()
        val owner = DeviceLocationOwner<String>(backgroundScope, { lookups++; location }, { 42L }, { saved += it })
        owner.restoreCached(location.copy(city = "Old city"))
        runCurrent()
        owner.confirmWidgetHome(location, owner.state.value) { true }
        runCurrent()
        assertEquals(VpnDeviceLocation(location, DeviceLocationStatus.CURRENT, 42L), owner.state.value)
        assertEquals(0, lookups)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aWidgetConfirmationCannotReplaceAnActiveForegroundRequestOrItsNewResult() = runTest {
        val fresh = location.copy(city = "Fresh foreground city")
        val deferred = CompletableDeferred<VpnLocation>()
        val owner = DeviceLocationOwner<String>(backgroundScope, { deferred.await() }, { 42L })
        owner.restoreCached(location)
        runCurrent()
        val expected = owner.state.value
        owner.networkChanged("direct-network")
        owner.setForeground(true)
        runCurrent()
        owner.confirmWidgetHome(location.copy(city = "Old widget city"), expected) { true }
        runCurrent()
        assertEquals(DeviceLocationStatus.LOADING, owner.state.value.status)
        deferred.complete(fresh)
        runCurrent()
        owner.setForeground(false)
        runCurrent()
        owner.confirmWidgetHome(location.copy(city = "Old widget city"), expected) { true }
        runCurrent()
        assertEquals(fresh, owner.state.value.location)
    }

    @Test
    fun connectedAndSupersededWidgetConfirmationsAreRejected() = runTest {
        val owner = DeviceLocationOwner<String>(backgroundScope, { location }, { 42L })
        owner.restoreCached(location)
        runCurrent()
        val expected = owner.state.value
        owner.confirmWidgetHome(location.copy(city = "Superseded"), expected) { false }
        runCurrent()
        assertEquals(location, owner.state.value.location)
        owner.setTunnelActive(true)
        runCurrent()
        owner.confirmWidgetHome(location.copy(city = "VPN exit"), owner.state.value) { true }
        runCurrent()
        assertEquals(location, owner.state.value.location)
        assertEquals(DeviceLocationStatus.CACHED, owner.state.value.status)
    }

    private companion object { val location = VpnLocation("US", "Example", "United States", "8.8.8.8") }
}
