package com.foxhole.vpn.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionExpiryTest {
    @Test fun millisecondsAreFormattedInTheDeviceTimeZone() {
        val expiry = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()
        assertEquals("Oct 8, 2026", formatSubscriptionExpiry(expiry, Locale.US, ZoneId.of("UTC")))
        assertEquals("Oct 7, 2026", formatSubscriptionExpiry(expiry, Locale.US, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun absentAndInvalidExpiryCannotProduceAnInventedDate() {
        for (value in listOf<Long?>(null, 0L, -1L, Long.MAX_VALUE)) {
            assertNull(formatSubscriptionExpiry(value, Locale.US, ZoneId.of("UTC")))
        }
    }
}
