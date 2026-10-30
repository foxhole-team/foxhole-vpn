package com.foxhole.vpn.widget

import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdatesTest {
    @Test
    fun deviceCountryAndCityRerenderWithoutDetailedErrorsOrIpChanges() = runTest {
        val states = MutableStateFlow(ready)
        val updates = mutableListOf<Pair<VpnUiState, VpnSettings>>()
        backgroundScope.launch {
            widgetRenderRequests(states, MutableStateFlow(VpnSettings()), MutableStateFlow(0L)).collect(updates::add)
        }
        runCurrent()
        states.value = ready.copy(deviceLocation = VpnDeviceLocation(location, DeviceLocationStatus.CURRENT, 123L))
        runCurrent()
        states.value = states.value.copy(message = "Detailed non-widget error")
        runCurrent()
        assertEquals(2, updates.size)
        states.value = states.value.copy(deviceLocation = states.value.deviceLocation.copy(
            location = location.copy(ip = "192.0.2.9", latitude = 40.0), checkedAtElapsedRealtimeMs = 456L,
        ))
        runCurrent()
        assertEquals(2, updates.size)
        states.value = states.value.copy(deviceLocation = states.value.deviceLocation.copy(location = location.copy(city = "New city")))
        runCurrent()
        assertEquals(3, updates.size)
        states.value = states.value.copy(deviceLocation = states.value.deviceLocation.copy(status = DeviceLocationStatus.CACHED))
        runCurrent()
        assertEquals(4, updates.size)
        states.value = states.value.copy(deviceLocation = states.value.deviceLocation.copy(status = DeviceLocationStatus.UNAVAILABLE))
        runCurrent()
        assertEquals(5, updates.size)
    }

    @Test
    fun connectedCityChangeRendersWithoutIpOrCoordinateUpdates() = runTest {
        val states = MutableStateFlow(ready.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true, location = location))
        val updates = mutableListOf<Pair<VpnUiState, VpnSettings>>()
        backgroundScope.launch {
            widgetRenderRequests(states, MutableStateFlow(VpnSettings()), MutableStateFlow(0L)).collect(updates::add)
        }
        runCurrent()
        states.value = states.value.copy(location = location.copy(ip = "192.0.2.9", latitude = 40.0))
        runCurrent()
        assertEquals(1, updates.size)
        states.value = states.value.copy(location = location.copy(city = "Another city"))
        runCurrent()
        assertEquals(2, updates.size)
        states.value = states.value.copy(location = location.copy(countryCode = "CA"))
        runCurrent()
        assertEquals(3, updates.size)
    }

    @Test
    fun visibleStateSettingsAndExplicitRefreshStillRender() = runTest {
        val states = MutableStateFlow(ready)
        val settings = MutableStateFlow(VpnSettings())
        val revisions = MutableStateFlow(0L)
        val updates = mutableListOf<Pair<VpnUiState, VpnSettings>>()
        backgroundScope.launch { widgetRenderRequests(states, settings, revisions).collect(updates::add) }
        runCurrent()
        states.value = ready.copy(phase = VpnPhase.CONNECTING)
        runCurrent()
        states.value = ready.copy(phase = VpnPhase.ERROR, hasOwnedTunnel = true)
        runCurrent()
        states.value = states.value.copy(hasOwnedTunnel = false)
        runCurrent()
        settings.value = VpnSettings(theme = VpnTheme.DARK)
        runCurrent()
        settings.value = settings.value.copy(language = VpnLanguage.RU)
        runCurrent()
        revisions.value++
        runCurrent()
        states.value = ready.copy(profiles = listOf(profile.copy(name = "Renamed")))
        runCurrent()
        val beforeInvisibleChange = updates.size
        states.value = states.value.copy(profiles = listOf(profile.copy(name = "Renamed again")))
        runCurrent()
        assertEquals(beforeInvisibleChange, updates.size)
        states.value = ready.copy(profiles = listOf(profile.copy(id = "replacement")), selectedProfileId = "replacement")
        runCurrent()
        assertEquals(9, updates.size)
    }

    private companion object {
        val profile = VpnProfileSummary("owned", "Owned profile", "VLESS")
        val ready = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)
        val location = VpnLocation("US", "Example", "United States", "192.0.2.8")
    }
}
