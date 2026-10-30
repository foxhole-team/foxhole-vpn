package com.foxhole.vpn.ui

import com.foxhole.vpn.VpnLocation
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GlobeMotionTest {
    @Test fun anAlreadyActiveConnectionInANewProcessStillGetsItsOwnConfirmationCue() {
        val cue = GlobeConnectionCue.restore(listOf("older-process", 1L), "new-process")
        assertTrue(cue.beginIfNew(1L, confirmed = true))
        assertFalse(cue.beginIfNew(1L, confirmed = true))
    }

    @Test fun warmRestorationKeepsTheCueButLegacyValuesDoNotClaimANewProcessTicket() {
        assertFalse(GlobeConnectionCue.restore(listOf("same-process", 1L), "same-process").beginIfNew(1L, true))
        for (legacy in listOf(null, 1L, "invalid", emptyList<Any>())) {
            assertTrue(GlobeConnectionCue.restore(legacy, "new-process").beginIfNew(1L, true))
        }
    }

    @Test
    fun releaseAngularSpeedMatchesTheFingerInsteadOfACompressedTravelTarget() {
        assertEquals(45f, globeAngularVelocity(100f, 400), .0001f)
        assertEquals(-45f, globeAngularVelocity(-100f, 400), .0001f)
        assertEquals(450f, globeAngularVelocity(1_000f, 400), .0001f)
    }

    @Test
    fun slowInvalidAndMissingSizeReleasesHaveNoInertia() {
        assertNull(globeFlingMotion(5f, 5f, 180))
        assertNull(globeFlingMotion(8f, 0f, 180))
        assertNull(globeFlingMotion(100f, 0f, 0))
        assertNull(globeFlingMotion(Float.NaN, 10f, 180))
        assertNull(globeFlingMotion(10f, Float.POSITIVE_INFINITY, 180))
    }

    @Test
    fun decayStartsAtReleaseVelocityAndOnlySlowsDown() {
        val fling = requireNotNull(globeFlingMotion(60f, -80f, 180))
        assertEquals(100f, fling.initialSpeed, .0001f)
        var previous = fling.initialSpeed
        for (milliseconds in listOf(0L, 1L, 16L, 50L, 100L, 200L, 400L)) {
            val velocity = fling.decay.getVelocityFromNanos(milliseconds * 1_000_000, 0f, fling.initialSpeed)
            assertTrue(velocity >= 0f && velocity <= previous)
            previous = velocity
        }
        val first = fling.decay.getValueFromNanos(1_000_000, 0f, fling.initialSpeed)
        val orientation = fling.orientationAt(GlobeOrientation(0f, 0f), first)
        assertEquals(60f, orientation.yaw / .001f, 2f)
        assertEquals(-80f, orientation.pitch / .001f, 2f)
    }

    @Test
    fun combinedTravelIsCappedWithoutChangingReleaseDirection() {
        for (velocity in listOf(60f, 300f, 1_000f, 100_000f)) {
            val fling = requireNotNull(globeFlingMotion(velocity, -velocity, 180))
            val target = fling.decay.getTargetValue(0f, fling.initialSpeed)
            assertTrue(target.isFinite() && target in 0f..52.001f)
            val last = fling.orientationAt(GlobeOrientation(0f, 0f), target)
            assertTrue(hypot(last.yaw, last.pitch) <= 52.001f)
            assertEquals(last.yaw, -last.pitch, .0001f)
            val bounded = fling.orientationAt(GlobeOrientation(0f, 0f), 500f)
            assertTrue(hypot(bounded.yaw, bounded.pitch) <= 52.001f)
        }
    }

    @Test
    fun ordinaryReleaseTailsSettleWithinTheShortPhysicalRange() {
        for (speed in listOf(60f, 150f, 300f)) {
            val fling = requireNotNull(globeFlingMotion(speed, 0f, 180))
            val duration = fling.decay.getDurationNanos(0f, fling.initialSpeed) / 1_000_000_000.0
            assertTrue("Tail duration $duration for $speed degrees per second", duration in .55..1.05)
        }
    }

    @Test fun increasinglyFastFlicksHaveTheSameGentleStartAndDeceleration() {
        val fast = requireNotNull(globeFlingMotion(1_000f, -1_000f, 180))
        val faster = requireNotNull(globeFlingMotion(100_000f, -100_000f, 180))
        assertTrue(fast.initialSpeed in 150f..170f)
        assertEquals(fast.initialSpeed, faster.initialSpeed, .0001f)
        for (time in listOf(0L, 16L, 100L, 400L, 900L)) {
            assertEquals(fast.decay.getVelocityFromNanos(time * 1_000_000, 0f, fast.initialSpeed),
                faster.decay.getVelocityFromNanos(time * 1_000_000, 0f, faster.initialSpeed), .0001f)
        }
        assertTrue(fast.decay.getTargetValue(0f, fast.initialSpeed) in 45f..52f)
    }

    @Test fun reducedMotionAndInactiveLifecycleRejectAutomaticInertia() {
        assertTrue(globeMotionAllowed(1f, true))
        assertFalse(globeMotionAllowed(1f, false))
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertFalse(globeMotionAllowed(scale, true))
    }

    @Test fun aLongHorizontalDragStillHasInertiaAtHighLatitudesWithoutAReleaseJump() {
        for (pitch in listOf(-75f, -55f, 55f, 75f)) {
            for (direction in listOf(-1f, 1f)) {
                val released = globeDragOrientation(GlobeOrientation(35f, pitch), direction * 250f, 0f, 400)
                val fling = requireNotNull(globeFlingMotion(direction * 100f, 0f, 400))
                assertEquals(released, fling.orientationAt(released, 0f))
                val distance = fling.decay.getValueFromNanos(16_000_000L, 0f, fling.initialSpeed)
                val next = fling.orientationAt(released, distance)
                val yaw = ((next.yaw - released.yaw) % 360f + 540f) % 360f - 180f
                assertEquals(direction * distance, yaw, .0001f)
                assertTrue(direction * yaw > 0f)
                assertEquals(pitch, next.pitch, .0001f)
            }
        }
    }

    @Test fun anAngularFlickAtEitherPoleStaysBoundedAndKeepsItsHorizontalDirection() {
        for (pole in listOf(-90f, 90f)) {
            val start = GlobeOrientation(179f, pole)
            val fling = requireNotNull(globeFlingMotion(10_000f, kotlin.math.sign(pole) * 10_000f, 400))
            assertTrue(fling.initialSpeed <= GlobeInertia.maximumSpeed)
            for (distance in listOf(0f, 5f, 20f, 50f, 100f)) {
                val next = fling.orientationAt(start, distance)
                val yaw = ((next.yaw - start.yaw) % 360f + 540f) % 360f - 180f
                assertTrue(hypot(yaw, next.pitch - start.pitch) <= GlobeInertia.maximumTravel + .001f)
                assertTrue(yaw >= 0f)
                assertEquals(pole, next.pitch, .0001f)
            }
        }
    }

    @Test fun overlappingTargetsChooseTheNearestPointAndTiesAlternateTheVisibleCard() {
        val device = Offset(100f, 100f)
        val server = Offset(116f, 100f)
        assertEquals(GlobeCallout.DEVICE, globeMarkerHit(device, server, Offset(104f, 100f), 24f, 2f, GlobeCallout.SERVER))
        assertEquals(GlobeCallout.SERVER, globeMarkerHit(device, server, Offset(112f, 100f), 24f, 2f, GlobeCallout.DEVICE))
        assertEquals(GlobeCallout.DEVICE, globeMarkerHit(device, server, Offset(108.5f, 100f), 24f, 2f, GlobeCallout.SERVER))
        assertEquals(GlobeCallout.SERVER, globeMarkerHit(device, device, device, 24f, 2f, GlobeCallout.DEVICE))
        assertEquals(GlobeCallout.DEVICE, globeMarkerHit(device, device, device, 24f, 2f, GlobeCallout.SERVER))
        assertEquals(GlobeCallout.SERVER, globeMarkerHit(device, device, device, 24f, 2f, GlobeCallout.NONE))
        assertEquals(GlobeCallout.DEVICE, globeMarkerHit(device, null, Offset(76f, 100f), 24f, 2f, GlobeCallout.NONE))
        assertEquals(GlobeCallout.NONE, globeMarkerHit(device, server, Offset(75f, 100f), 24f, 2f, GlobeCallout.NONE))
        assertEquals(GlobeCallout.NONE, globeMarkerHit(device, server, device, Float.NaN, 2f, GlobeCallout.NONE))
    }

    @Test
    fun ticksRequireRealDragDistanceAndNeverCatchUpFromAStationaryEvent() {
        val gate = GlobeDragHaptics()
        assertFalse(gate.onDrag(GlobeOrientation(0f, 0f), GlobeOrientation(90f, 0f), 0))
        gate.beginDrag()
        assertFalse(gate.onDrag(GlobeOrientation(0f, 0f), GlobeOrientation(10f, 0f), 0))
        assertTrue(gate.onDrag(GlobeOrientation(10f, 0f), GlobeOrientation(11f, 0f), 1))
        assertFalse(gate.onDrag(GlobeOrientation(11f, 0f), GlobeOrientation(40f, 0f), 80))
        assertFalse(gate.onDrag(GlobeOrientation(40f, 0f), GlobeOrientation(40f, 0f), 81))
        assertTrue(gate.onDrag(GlobeOrientation(40f, 0f), GlobeOrientation(41f, 0f), 81))
        gate.endDrag()
        assertFalse(gate.onDrag(GlobeOrientation(41f, 0f), GlobeOrientation(90f, 0f), 200))
    }

    @Test
    fun tickThrottleSurvivesQuickSuccessiveGestures() {
        val gate = GlobeDragHaptics()
        gate.beginDrag()
        assertTrue(gate.onDrag(GlobeOrientation(0f, 0f), GlobeOrientation(11f, 0f), 0))
        gate.endDrag()
        gate.beginDrag()
        assertFalse(gate.onDrag(GlobeOrientation(0f, 0f), GlobeOrientation(11f, 0f), 79))
        assertTrue(gate.onDrag(GlobeOrientation(11f, 0f), GlobeOrientation(12f, 0f), 80))
    }

    @Test
    fun dateLineClampedPoleAndInvalidEventsDoNotInventTickDistance() {
        val gate = GlobeDragHaptics()
        gate.beginDrag()
        assertFalse(gate.onDrag(GlobeOrientation(179f, 0f), GlobeOrientation(-179f, 0f), 100))
        assertFalse(gate.onDrag(GlobeOrientation(-179f, 90f), GlobeOrientation(-179f, 90f), 110))
        assertFalse(gate.onDrag(GlobeOrientation(Float.NaN, 0f), GlobeOrientation(90f, 0f), 120))
        assertFalse(gate.onDrag(GlobeOrientation(0f, 0f), GlobeOrientation(90f, 0f), 99))
        assertTrue(gate.onDrag(GlobeOrientation(-179f, 0f), GlobeOrientation(-170f, 0f), 180))
    }

    @Test
    fun connectionCueWaitsForValidatedIpAndUsableServerCoordinates() {
        val location = VpnLocation("US", "Example", "United States", "203.0.113.8", 40.0, -75.0)
        assertFalse(validatedGlobeExit(false, location))
        assertFalse(validatedGlobeExit(true, null))
        assertFalse(validatedGlobeExit(true, location.copy(ip = "")))
        assertFalse(validatedGlobeExit(true, location.copy(latitude = null)))
        assertFalse(validatedGlobeExit(true, location.copy(longitude = Double.NaN)))
        assertFalse(validatedGlobeExit(true, location.copy(latitude = 91.0)))
        assertTrue(validatedGlobeExit(true, location))
    }

    @Test
    fun connectionCueConsumesOnceAfterLateGeoAndDoesNotRepeatForHealthOrCancellation() {
        val cue = GlobeConnectionCue()
        assertFalse(cue.beginIfNew(1, confirmed = false))
        assertTrue(cue.beginIfNew(1, confirmed = true))
        assertFalse(cue.beginIfNew(1, confirmed = true))
        assertFalse(cue.beginIfNew(1, confirmed = false))
        assertFalse(cue.beginIfNew(1, confirmed = true))
        val restored = GlobeConnectionCue(cue.consumedConnectionId)
        assertFalse(restored.beginIfNew(1, confirmed = true))
        assertTrue(restored.beginIfNew(2, confirmed = true))
        assertFalse(restored.beginIfNew(null, confirmed = false))
        assertTrue(restored.beginIfNew(1, confirmed = true))
    }
}
