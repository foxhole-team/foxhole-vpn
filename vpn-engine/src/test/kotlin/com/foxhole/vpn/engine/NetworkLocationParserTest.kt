package com.foxhole.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkLocationParserTest {
    @Test
    fun validApproximateCoordinatesUseTheExistingLocationPayload() {
        val parsed = parseNetworkLocation(payload("0.0", "-180.0"))
        assertEquals(0.0, requireNotNull(parsed.latitude), 0.0)
        assertEquals(-180.0, requireNotNull(parsed.longitude), 0.0)
        assertEquals("US", parsed.countryCode)
    }

    @Test
    fun invalidOrPartialCoordinatesCannotProduceAGlobePoint() {
        for ((latitude, longitude) in listOf("91" to "2", "1" to "181", "null" to "2",
            "1" to "null", "\"NaN\"" to "2", "{}" to "2")) {
            val parsed = parseNetworkLocation(payload(latitude, longitude))
            assertNull(parsed.latitude)
            assertNull(parsed.longitude)
            assertEquals("Example", parsed.city)
        }
    }

    @Test(expected = EngineException::class)
    fun privateAddressDoesNotBecomePublicGeography() {
        parseNetworkLocation(payload("1", "2").replace("8.8.8.8", "192.168.1.1"))
    }

    private fun payload(latitude: String, longitude: String) =
        """{"success":true,"ip":"8.8.8.8","country_code":"us","city":"Example","country":"United States","latitude":$latitude,"longitude":$longitude}"""
}
