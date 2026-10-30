package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeDragMotionTest {
    @Test fun longHorizontalDragsKeepTheirDirectionAndTiltAtHomeLatitudes() {
        for (pitch in listOf(-75f, -55f, 55f, 75f)) {
            for (direction in listOf(-1f, 1f)) {
                var current = GlobeOrientation(35f, pitch)
                repeat(10) {
                    val previous = current
                    current = globeDragOrientation(current, direction * 25f, 0f, 400)
                    assertEquals(direction * 11.25f, yawChange(previous, current), .0001f)
                    assertEquals(pitch, current.pitch, .0001f)
                }
                assertEquals(35f + direction * 112.5f, current.yaw, .0001f)
            }
        }
    }

    @Test fun longDiagonalDragsKeepAdvancingHorizontallyAfterThePitchReachesAPole() {
        for (pitch in listOf(-75f, -55f, 55f, 75f)) {
            for (direction in listOf(-1f, 1f)) {
                var current = GlobeOrientation(35f, pitch)
                repeat(10) {
                    val previous = current
                    current = globeDragOrientation(current, direction * 25f, direction * 25f, 400)
                    assertEquals(direction * 11.25f, yawChange(previous, current), .0001f)
                    assertEquals((previous.pitch + direction * 11.25f).coerceIn(-90f, 90f), current.pitch, .0001f)
                }
                assertEquals((pitch + direction * 112.5f).coerceIn(-90f, 90f), current.pitch, .0001f)
            }
        }
    }

    @Test fun aVerticalDragDoesNotIntroduceYawAndReversesImmediatelyAtEitherPole() {
        for (direction in listOf(-1f, 1f)) {
            val initial = GlobeOrientation(35f, direction * 55f)
            val pole = globeDragOrientation(initial, 0f, direction * 250f, 400)
            assertEquals(initial.yaw, pole.yaw, .0001f)
            assertEquals(direction * 90f, pole.pitch, .0001f)
            val reversed = globeDragOrientation(pole, 0f, direction * -25f, 400)
            assertEquals(initial.yaw, reversed.yaw, .0001f)
            assertEquals(direction * 78.75f, reversed.pitch, .0001f)
        }
    }

    @Test fun reversingALongHorizontalDragRestoresTheStartingOrientation() {
        for (pitch in listOf(-75f, -55f, 55f, 75f)) {
            val initial = GlobeOrientation(35f, pitch)
            val forward = globeDragOrientation(initial, 250f, 0f, 400)
            val returned = globeDragOrientation(forward, -250f, 0f, 400)
            assertEquals(initial.yaw, returned.yaw, .0001f)
            assertEquals(initial.pitch, returned.pitch, .0001f)
        }
    }

    @Test fun changingDirectionDuringAMixedDragHasNoAnchorOrEdgeDeadZone() {
        val initial = GlobeOrientation(35f, 55f)
        val right = globeDragOrientation(initial, 250f, 0f, 400)
        val up = globeDragOrientation(right, 0f, -40f, 400)
        val left = globeDragOrientation(up, -250f, 0f, 400)
        val down = globeDragOrientation(left, 0f, 40f, 400)
        assertEquals(right.yaw, up.yaw, .0001f)
        assertEquals(37f, up.pitch, .0001f)
        assertEquals(initial.yaw, left.yaw, .0001f)
        assertEquals(initial.yaw, down.yaw, .0001f)
        assertEquals(initial.pitch, down.pitch, .0001f)
    }

    @Test fun crossingTheDateLineKeepsTheDragDirectionAndCanBeReversed() {
        val initial = GlobeOrientation(179f, 75f)
        val forward = globeDragOrientation(initial, 25f, 0f, 400)
        assertEquals(-169.75f, forward.yaw, .0001f)
        assertEquals(11.25f, yawChange(initial, forward), .0001f)
        assertEquals(initial, globeDragOrientation(forward, -25f, 0f, 400))
    }

    @Test fun gestureSamplingAndDisplaySizeDoNotChangeTheTotalRotation() {
        val initial = GlobeOrientation(-35f, -55f)
        val whole = globeDragOrientation(initial, 250f, 100f, 400)
        var sampled = initial
        repeat(10) { sampled = globeDragOrientation(sampled, 25f, 10f, 400) }
        val doubled = globeDragOrientation(initial, 500f, 200f, 800)
        assertEquals(whole.yaw, sampled.yaw, .0001f)
        assertEquals(whole.pitch, sampled.pitch, .0001f)
        assertEquals(whole, doubled)
    }

    @Test fun stationaryInvalidAndMissingSizeEventsLeaveTheGlobeAlone() {
        val initial = GlobeOrientation(35f, 55f)
        assertEquals(initial, globeDragOrientation(initial, 0f, 0f, 400))
        for (width in listOf(0, -1)) assertEquals(initial, globeDragOrientation(initial, 250f, 250f, width))
        for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(initial, globeDragOrientation(initial, invalid, 25f, 400))
            assertEquals(initial, globeDragOrientation(initial, 25f, invalid, 400))
            val invalidOrientation = initial.copy(pitch = invalid)
            assertEquals(invalidOrientation, globeDragOrientation(invalidOrientation, 25f, 25f, 400))
        }
        val next = globeDragOrientation(initial, 250f, 250f, 400)
        assertTrue(next.yaw.isFinite() && next.pitch.isFinite())
    }

    private fun yawChange(previous: GlobeOrientation, current: GlobeOrientation): Float =
        ((current.yaw - previous.yaw) % 360f + 540f) % 360f - 180f
}
