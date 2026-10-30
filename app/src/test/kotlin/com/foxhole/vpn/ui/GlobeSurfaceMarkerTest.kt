package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GlobeSurfaceMarkerTest {
    @Test fun aFrontMarkerIsRoundAndAnchoredToTheSphere() {
        val marker = requireNotNull(globeSurfaceMarker(floatArrayOf(0f, 0f, 1f)))
        assertEquals(200f, marker.center.x, 0f)
        assertEquals(193f, marker.center.y, 0f)
        assertEquals(1f, marker.radialScale, 0f)
        assertEquals(1f, marker.opacity, 0f)
    }

    @Test fun surfaceDiscsForeshortenRadiallyWhileRotatingAroundTheGlobe() {
        for (longitude in listOf(30f, 60f, 80f)) {
            val point = GlobeProjection.spherePoint(longitude, 0f)
            val marker = requireNotNull(globeSurfaceMarker(point))
            assertEquals(point[2], marker.radialScale, .0001f)
            assertEquals(0f, marker.rotationDegrees, .0001f)
            assertEquals(200f + 145f * point[0], marker.center.x, .0001f)
        }
        val north = requireNotNull(globeSurfaceMarker(GlobeProjection.spherePoint(0f, 60f)))
        val south = requireNotNull(globeSurfaceMarker(GlobeProjection.spherePoint(0f, -60f)))
        assertEquals(-90f, north.rotationDegrees, .0001f)
        assertEquals(90f, south.rotationDegrees, .0001f)
        assertEquals(.5f, north.radialScale, .0001f)
    }

    @Test fun aMarkerFadesBeforeOcclusionWithoutJumpingAtTheLimb() {
        var previousOpacity = 1f
        for (depth in listOf(.18f, .15f, .1f, .05f, .001f)) {
            val marker = requireNotNull(globeSurfaceMarker(floatArrayOf(sqrt(1f - depth * depth), 0f, depth)))
            assertTrue(marker.opacity in 0f..previousOpacity)
            assertEquals(depth, marker.radialScale, 0f)
            previousOpacity = marker.opacity
        }
        assertTrue(previousOpacity < .0001f)
        assertNull(globeSurfaceMarker(floatArrayOf(1f, 0f, 0f)))
        assertNull(globeSurfaceMarker(floatArrayOf(0f, 0f, -1f)))
    }

    @Test fun rotationPreservesTheHitAnchorAndHidesTheRearMarker() {
        val point = GlobeProjection.spherePoint(35f, 20f)
        val front = GlobeProjection(GlobeOrientation(-35f, 20f)).rotate(point)
        val marker = requireNotNull(globeSurfaceMarker(front))
        assertEquals(200f, marker.center.x, .0001f)
        assertEquals(193f, marker.center.y, .0001f)
        assertEquals(1f, marker.radialScale, .0001f)
        assertNull(globeSurfaceMarker(GlobeProjection(GlobeOrientation(145f, -20f)).rotate(point)))
    }

    @Test fun invalidMarkersCannotIntroducePaintTransforms() {
        for (point in listOf(FloatArray(0), floatArrayOf(0f, 1f), floatArrayOf(Float.NaN, 0f, 1f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY, 1f), floatArrayOf(0f, 0f, Float.NaN))) {
            assertNull(globeSurfaceMarker(point))
        }
    }
}
