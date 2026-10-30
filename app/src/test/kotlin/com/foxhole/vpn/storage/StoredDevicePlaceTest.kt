package com.foxhole.vpn.storage

import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnLocation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredDevicePlaceTest {
    @Test
    fun `old schema payloads keep their settings and default to no location cache`() {
        val decoded = Json.decodeFromString<ProfileStoreState>("""{"schema":1,"selectedProfileId":null}""")
        assertNull(decoded.lastKnownDevicePlace)
        assertEquals(ProfileStoreState(), decoded)
    }

    @Test
    fun `one cached home retains a bounded globe position without IP or history`() {
        val location = VpnLocation("de", "DeviceCityFixture", "DeviceCountryFixture", "192.0.2.12", 52.5, 13.4)
        val cached = requireNotNull(storedDevicePlace(location))
        val encoded = Json.encodeToString(cached)
        assertFalse(encoded.contains(location.ip))
        assertFalse(encoded.contains("history"))
        val restored = requireNotNull(cached.restore())
        assertEquals(DeviceLocationStatus.CACHED, restored.status)
        assertEquals(null, restored.checkedAtElapsedRealtimeMs)
        assertEquals(VpnLocation("DE", location.city, location.country, "", 52.5, 13.4), restored.location)
    }

    @Test
    fun `malformed optional cache cannot become a current or unsafe place`() {
        assertNull(StoredDevicePlace("../", "City", "Country").restore())
        assertNull(storedDevicePlace(VpnLocation("DE", "x".repeat(257), "Country", "192.0.2.1")))
        assertNull(storedDevicePlace(VpnLocation("DE", "City", "x".repeat(257), "192.0.2.1")))
    }

    @Test
    fun `malformed optional cache values do not discard legal profile settings`() {
        listOf("true", "[]", "\"invalid\"", """{"countryCode":12,"city":[],"country":"Country"}""").forEach { cache ->
            val decoded = Json.decodeFromString<ProfileStoreState>(
                """{"schema":1,"lastKnownDevicePlace":$cache}""",
            )
            assertEquals(ProfileStoreState().profiles, decoded.profiles)
            assertEquals(ProfileStoreState().settings, decoded.settings)
            assertNull(decoded.lastKnownDevicePlace?.restore())
        }
    }

    @Test
    fun `optional cache cannot weaken unknown profile field rejection`() {
        var refused = false
        try { Json.decodeFromString<ProfileStoreState>("""{"schema":1,"unknownProfileField":true}""") }
        catch (_: Exception) { refused = true }
        assertTrue(refused)
    }

    @Test
    fun `old place records without coordinates remain a marked last known city`() {
        val place = Json.decodeFromString<StoredDevicePlace>("""{"countryCode":"DE","city":"City","country":"Country"}""")
        assertEquals(VpnLocation("DE", "City", "Country", ""), place.restore()?.location)
        assertEquals(DeviceLocationStatus.CACHED, place.restore()?.status)
    }

    @Test
    fun `coordinates must be finite within bounds and present together`() {
        listOf(91.0 to 13.4, 52.5 to 181.0, Double.NaN to 13.4, 52.5 to Double.POSITIVE_INFINITY,
            null to 13.4, 52.5 to null).forEach { (latitude, longitude) ->
            val restored = StoredDevicePlace("DE", "City", "Country", latitude, longitude).restore()
            assertEquals(null, restored?.location?.latitude)
            assertEquals(null, restored?.location?.longitude)
            assertEquals(DeviceLocationStatus.CACHED, restored?.status)
        }
    }
}
