package com.foxhole.vpn.widget

import com.foxhole.vpn.R
import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetLocationTest {
    @Test fun `cached location uses the city without a long status caption`() {
        assertEquals("Berlin", widgetLocationLabel(WidgetLocation("DE", "Berlin", cached = true)))
        assertEquals("Berlin", widgetLocationLabel(WidgetLocation("DE", "Berlin", cached = false)))
        assertNull(widgetLocationLabel(null))
        assertNull(widgetLocationLabel(WidgetLocation("DE", "", cached = true)))
    }

    private val home = VpnLocation("DE", "Berlin", "Germany", "192.0.2.1")
    private val exit = VpnLocation("NL", "Amsterdam", "Netherlands", "192.0.2.2")
    private val disconnected = VpnUiState(
        isReady = true, location = exit,
        deviceLocation = VpnDeviceLocation(home, DeviceLocationStatus.CURRENT, 123L),
    )

    @Test
    fun `disconnected uses device city instead of a previous vpn exit`() {
        assertEquals(WidgetLocation("DE", "Berlin", cached = false), widgetLocation(disconnected))
    }

    @Test
    fun `connected uses only the validated vpn city`() {
        val connected = disconnected.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        assertEquals(WidgetLocation("NL", "Amsterdam", cached = false), widgetLocation(connected))
        assertNull(widgetLocation(connected.copy(location = null)))
    }

    @Test
    fun `cached device data is explicitly distinguished from current data`() {
        assertEquals(WidgetLocation("DE", "Berlin", cached = true), widgetLocation(disconnected.copy(
            deviceLocation = disconnected.deviceLocation.copy(status = DeviceLocationStatus.CACHED),
        )))
        listOf(DeviceLocationStatus.UNKNOWN, DeviceLocationStatus.LOADING, DeviceLocationStatus.UNAVAILABLE).forEach { status ->
            assertNull(widgetLocation(disconnected.copy(deviceLocation = disconnected.deviceLocation.copy(status = status))))
        }
    }

    @Test
    fun `pending validation retained error and unhydrated storage hide geography`() {
        listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.STOPPING, VpnPhase.ERROR).forEach { phase ->
            assertNull(widgetLocation(disconnected.copy(phase = phase, hasOwnedTunnel = true)))
        }
        assertNull(widgetLocation(disconnected.copy(isReady = false)))
    }

    @Test
    fun `unknown city can show the actual country without inventing a city`() {
        val state = disconnected.copy(deviceLocation = disconnected.deviceLocation.copy(location = home.copy(city = "")))
        assertEquals(WidgetLocation("DE", "Germany", cached = false), widgetLocation(state))
    }

    @Test
    fun `connection and validation show a determining location without retaining previous geography`() {
        listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING).forEach { phase ->
            val pending = disconnected.copy(phase = phase, hasOwnedTunnel = true)
            assertEquals(R.string.widget_location_determining, widgetLocationPlaceholder(pending))
            assertNull(widgetLocation(pending))
        }
    }

    @Test
    fun `an explicit idle location request is determining until it completes`() {
        val pending = disconnected.copy(deviceLocation = disconnected.deviceLocation.copy(status = DeviceLocationStatus.LOADING))
        assertEquals(R.string.widget_location_determining, widgetLocationPlaceholder(pending))
        assertNull(widgetLocation(pending))
        assertEquals(R.string.widget_detail_empty, widgetLocationPlaceholder(disconnected))
    }

    @Test
    fun `errors stopping and missing geography do not invent a pending location request`() {
        listOf(VpnPhase.ERROR, VpnPhase.STOPPING, VpnPhase.CONNECTED).forEach { phase ->
            val terminal = disconnected.copy(phase = phase, hasOwnedTunnel = true, location = null,
                deviceLocation = disconnected.deviceLocation.copy(status = DeviceLocationStatus.LOADING))
            assertEquals(R.string.widget_detail_empty, widgetLocationPlaceholder(terminal))
            assertNull(widgetLocation(terminal))
        }
        listOf(DeviceLocationStatus.UNKNOWN, DeviceLocationStatus.UNAVAILABLE).forEach { status ->
            assertEquals(R.string.widget_detail_empty, widgetLocationPlaceholder(disconnected.copy(
                deviceLocation = disconnected.deviceLocation.copy(status = status),
            )))
        }
        assertEquals(R.string.widget_detail_empty, widgetLocationPlaceholder(disconnected.copy(isReady = false,
            deviceLocation = disconnected.deviceLocation.copy(status = DeviceLocationStatus.LOADING))))
    }
}
