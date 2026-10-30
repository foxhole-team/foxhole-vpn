package com.foxhole.vpn

import com.foxhole.vpn.refresh.RefreshAttempt
import com.foxhole.vpn.refresh.RefreshOutcome
import com.foxhole.vpn.refresh.refreshIsDue
import com.foxhole.vpn.refresh.subscriptionSourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRefreshPolicyTest {
    @Test fun successfulChecksWaitTwelveHoursAndFutureClockValuesNeverSuppressChecks() {
        val interval = 12 * 60 * 60 * 1000L
        val checked = RefreshAttempt(
            lastAttemptAtMillis = 1_000,
            lastSuccessAtMillis = 1_000,
            outcome = RefreshOutcome.UPDATED
        )
        assertFalse(refreshIsDue(checked, 1_000 + interval - 1, interval))
        assertTrue(refreshIsDue(checked, 1_000 + interval, interval))
        assertTrue(refreshIsDue(checked, 999, interval))
        assertTrue(refreshIsDue(null, 1_000, interval))
    }

    @Test fun failuresAndDeferredActiveUpdatesNeverBecomeSuccessfulChecks() {
        val failed = RefreshAttempt(
            lastAttemptAtMillis = 10_000,
            lastSuccessAtMillis = 1_000,
            outcome = RefreshOutcome.FAILED
        )
        val deferred = failed.copy(outcome = RefreshOutcome.DEFERRED)
        assertEquals(1_000L, deferred.lastSuccessAtMillis)
        assertFalse(refreshIsDue(failed, 10_001, 1_000))
        assertTrue(refreshIsDue(failed, 10_000 + 10 * 60 * 1000L, 1_000))
        assertTrue(refreshIsDue(deferred, 10_000 + 10 * 60 * 1000L, 1_000))
    }

    @Test fun sourceKeysDoNotPersistPrivateSubscriptionUrlsInWorkMetadata() {
        val source = "https://example.com/subscription?token=synthetic-secret"
        val key = subscriptionSourceKey(source)
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(key.contains("synthetic-secret"))
        assertEquals(key, subscriptionSourceKey(source))
        assertFalse(key == subscriptionSourceKey(source + "-other"))
    }
}
