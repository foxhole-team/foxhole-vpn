package com.foxhole.vpn.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeCalloutPlacementTest {
    @Test fun visibleFrontAnchorAndOrdinaryDeviceProjectionStayUnchanged() {
        val output = FloatArray(2)
        placeGlobeCallout(floatArrayOf(.3f, -.4f, .5f), true, output)
        assertArrayEquals(floatArrayOf(.3f, -.4f), output, .00001f)
        placeGlobeCallout(floatArrayOf(.3f, -.4f, -.5f), false, output)
        assertArrayEquals(floatArrayOf(.3f, -.4f), output, .00001f)
    }

    @Test fun crossingTheHorizonDoesNotJumpTheExpandedServerCard() {
        val front = FloatArray(2)
        val rear = FloatArray(2)
        placeGlobeCallout(floatArrayOf(.8f, -.6f, .00001f), true, front)
        placeGlobeCallout(floatArrayOf(.8f, -.6f, -.00001f), true, rear)
        assertArrayEquals(front, rear, .0001f)
    }

    @Test fun rearProjectionApproachesAStableUpperGlobeOverlayWithoutMovingTheMarker() {
        val output = FloatArray(2)
        for (depth in listOf(-.1f, -.5f, -.9f, -1f)) {
            val point = floatArrayOf(.2f, -.3f, depth)
            val original = point.copyOf()
            placeGlobeCallout(point, true, output)
            assertTrue(output.all { it.isFinite() && it in -1f..1f })
            assertArrayEquals(original, point, 0f)
        }
        placeGlobeCallout(floatArrayOf(0f, 0f, -1f), true, output)
        assertEquals(0f, output[0], 0f)
        assertEquals(1f, output[1], 0f)
    }

    @Test fun aServerCardMovesBelowItsPointWhenHomeAndTheIncomingRouteAreAbove() {
        val route = GlobeRoute(GlobeCoordinates.from(68.7, 15.4), GlobeCoordinates.from(56.9, 24.1))
        val projection = GlobeProjection(requireNotNull(route.center))
        val home = screenPoint(projection.rotate(requireNotNull(route.device)))
        val server = screenPoint(projection.rotate(requireNotNull(route.exit)))
        assertTrue(globeCalloutBelow(home, server, 80f, 410f, 8f))
        val top = globeCalloutVerticalOffset(server.y, 80f, 410f, 8f, below = true)
        assertTrue(top > server.y && top > home.y)
        for (index in route.arc.indices step 3) {
            val point = projection.rotate(route.arc.copyOfRange(index, index + 3))
            assertTrue(screenPoint(point).y < top)
        }
    }

    @Test fun reverseEquatorialMissingRearAndCrampedRoutesKeepTheAboveCard() {
        assertFalse(globeCalloutBelow(Offset(200f, 220f), Offset(200f, 170f), 80f, 410f, 8f))
        assertFalse(globeCalloutBelow(Offset(175f, 193f), Offset(225f, 193f), 80f, 410f, 8f))
        assertFalse(globeCalloutBelow(null, Offset(200f, 220f), 80f, 410f, 8f))
        assertFalse(globeCalloutBelow(Offset(200f, 170f), null, 80f, 410f, 8f))
        assertFalse(globeCalloutBelow(Offset(200f, 170f), Offset(200f, 360f), 80f, 410f, 8f))
        assertFalse(globeCalloutBelow(Offset(200f, 170f), Offset(200f, 220f), 0f, 410f, 8f))
    }

    @Test fun verticalPlacementStaysInsideTheViewportAndInvalidInputNeverBecomesATransform() {
        assertEquals(0f, globeCalloutVerticalOffset(48f, 80f, 410f, 8f, below = false), 0f)
        assertEquals(330f, globeCalloutVerticalOffset(400f, 80f, 410f, 8f, below = true), 0f)
        assertEquals(0f, globeCalloutVerticalOffset(193f, 500f, 410f, 8f, below = false), 0f)
        assertEquals(0f, globeCalloutVerticalOffset(Float.NaN, 80f, 410f, 8f, below = false), 0f)
        assertFalse(globeCalloutBelow(Offset(200f, Float.NaN), Offset(200f, 220f), 80f, 410f, 8f))
    }

    private fun screenPoint(point: FloatArray) = Offset(200f + 145f * point[0], 193f - 145f * point[1])
}
