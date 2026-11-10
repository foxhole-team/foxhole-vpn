package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeAnimatorCompatibilityTest {
    @Test fun androidSevenUsesTheSystemScaleWithoutResolvingTheOreoApi() {
        for (sdk in listOf(24, 25)) {
            assertEquals(.5f, globeAnimatorDurationScale(sdk, { .5f }, { error("Oreo animator API unavailable") }))
        }
    }

    @Test fun androidSevenRespectsDisabledAndInvalidMotionScales() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val actual = globeAnimatorDurationScale(24, { scale }, { error("Oreo animator API unavailable") })
            assertFalse(globeMotionAllowed(actual, resumed = true))
        }
        assertTrue(globeMotionAllowed(globeAnimatorDurationScale(25, { 2f }, { error("Oreo animator API unavailable") }), true))
    }

    @Test fun oreoAndLaterKeepTheExistingPlatformAnimatorDecision() {
        for (sdk in listOf(26, 29, 37)) {
            for (enabled in listOf(false, true)) {
                var reads = 0
                assertEquals(if (enabled) 1f else 0f, globeAnimatorDurationScale(sdk,
                    { error("Legacy setting is not authoritative") }, { reads++; enabled }))
                assertEquals(1, reads)
            }
        }
    }
}
