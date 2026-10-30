package com.foxhole.vpn.widget

import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetHomeLocationRefreshTest {
    @Test
    fun anOsUpdateOrCompletedStopChecksHomeOnceWithoutPolling() = runTest {
        var requests = 0
        val saved = mutableListOf<VpnLocation>()
        repeat(2) {
            assertTrue(refreshWidgetHomeLocation({ true }, { "direct-network" }, {
                requests++
                home.copy(city = "City $requests")
            }, { _, place -> saved += place; true }))
        }
        assertEquals(2, requests)
        assertEquals(listOf("City 1", "City 2"), saved.map { it.city })
    }

    @Test
    fun absentWidgetsOrAnOwnedOrBusyTunnelDoNotPerformLookup() = runTest {
        var requests = 0
        val idle = VpnUiState(isReady = true)
        val cases = listOf(
            Triple(idle, false, false),
            Triple(idle.copy(isReady = false), true, false),
            Triple(idle, true, true),
            Triple(idle.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true), true, false),
            Triple(idle.copy(phase = VpnPhase.CONNECTING), true, false),
            Triple(idle.copy(phase = VpnPhase.VALIDATING), true, false),
            Triple(idle.copy(phase = VpnPhase.STOPPING), true, false),
            Triple(idle.copy(phase = VpnPhase.ERROR, hasOwnedTunnel = true), true, false),
        )
        cases.forEach { (state, widgets, pending) ->
            assertFalse(refreshWidgetHomeLocation({ widgetHomeRefreshAllowed(state, widgets, pending) }, { "direct-network" }, {
                requests++; home
            }, { _, _ -> error("Unexpected commit") }))
        }
        assertEquals(0, requests)
        assertTrue(widgetHomeRefreshAllowed(idle, hasWidgets = true, hasPendingStart = false))
        assertTrue(widgetHomeRefreshAllowed(idle.copy(phase = VpnPhase.ERROR), hasWidgets = true, hasPendingStart = false))
    }

    @Test
    fun unavailableOrVpnDefaultNetworkNeverPerformsLookup() = runTest {
        assertFalse(refreshWidgetHomeLocation<String>({ true }, { null }, {
            error("Unexpected lookup")
        }, { _, _ -> error("Unexpected commit") }))
    }

    @Test
    fun aStartDuringLookupRejectsTheLateResult() = runTest {
        var admitted = true
        val deferred = CompletableDeferred<VpnLocation>()
        var commits = 0
        val request = async {
            refreshWidgetHomeLocation({ admitted }, { "direct-network" }, { deferred.await() }, { _, _ -> commits++; true })
        }
        runCurrent()
        admitted = false
        deferred.complete(home)
        assertFalse(request.await())
        assertEquals(0, commits)
    }

    @Test
    fun changedNetworkOrRemovedWidgetRejectsTheLateResult() = runTest {
        var network = "network-a"
        val deferred = CompletableDeferred<VpnLocation>()
        var commits = 0
        val request = async {
            refreshWidgetHomeLocation({ true }, { network }, { deferred.await() }, { _, _ -> commits++; true })
        }
        runCurrent()
        network = "network-b"
        deferred.complete(home)
        assertFalse(request.await())
        assertEquals(0, commits)
    }

    @Test
    fun workerCancellationCannotCommitOrBecomeARetry() = runTest {
        var commits = 0
        val request = async {
            refreshWidgetHomeLocation({ true }, { "direct-network" }, {
                throw CancellationException("Widget cancellation fixture")
            }, { _, _ -> commits++; true })
        }
        runCurrent()
        assertTrue(request.isCancelled)
        assertEquals(0, commits)
    }

    private companion object {
        val home = VpnLocation("DE", "Berlin", "Germany", "192.0.2.1")
    }
}
