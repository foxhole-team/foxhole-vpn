package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeInertiaTest {
    @Test fun anOrdinaryFlickKeepsItsInitialVelocityAndDeceleratesContinuously() {
        var lastVelocity = 100f
        var lastDistance = 0f
        for (time in listOf(0L, 1_000_000L, 16_000_000L, 100_000_000L, 400_000_000L)) {
            val velocity = GlobeInertia.velocity(100f, time)
            val distance = GlobeInertia.distance(100f, time)
            assertTrue(velocity in 0f..lastVelocity)
            assertTrue(distance >= lastDistance)
            lastVelocity = velocity
            lastDistance = distance
        }
        assertEquals(100f, GlobeInertia.velocity(100f, 0L), 0f)
        assertEquals(.1f, GlobeInertia.distance(100f, 1_000_000L), .001f)
    }

    @Test fun aFastFlickSettlesOnceAndDoesNotContinueRequestingMotion() {
        val duration = GlobeInertia.durationNanos(GlobeInertia.maximumSpeed)
        assertTrue(duration in 950_000_000L..1_050_000_000L)
        val target = GlobeInertia.target(GlobeInertia.maximumSpeed)
        assertTrue(target in 45f..GlobeInertia.maximumTravel)
        for (time in listOf(duration, duration + 1_000_000L, 60_000_000_000L)) {
            assertEquals(target, GlobeInertia.distance(GlobeInertia.maximumSpeed, time), .0001f)
            assertEquals(0f, GlobeInertia.velocity(GlobeInertia.maximumSpeed, time), 0f)
        }
    }

    @Test fun oppositeDirectionsHaveTheSameBoundedPhysics() {
        assertEquals(GlobeInertia.durationNanos(100f), GlobeInertia.durationNanos(-100f))
        assertEquals(-GlobeInertia.target(100f), GlobeInertia.target(-100f), 0f)
        for (time in listOf(0L, 100_000_000L, 1_000_000_000L)) {
            assertEquals(-GlobeInertia.distance(100f, time), GlobeInertia.distance(-100f, time), 0f)
            assertEquals(-GlobeInertia.velocity(100f, time), GlobeInertia.velocity(-100f, time), 0f)
        }
    }

    @Test fun invalidOrAlreadySettledInputHasNoTail() {
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 4f, -4f, GlobeInertia.stopSpeed)) {
            assertEquals(0L, GlobeInertia.durationNanos(speed))
            assertEquals(0f, GlobeInertia.target(speed), 0f)
            assertEquals(0f, GlobeInertia.distance(speed, 1_000_000L), 0f)
            assertEquals(0f, GlobeInertia.velocity(speed, 1_000_000L), 0f)
        }
    }
}
