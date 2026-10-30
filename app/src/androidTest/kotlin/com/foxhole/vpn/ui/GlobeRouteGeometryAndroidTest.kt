package com.foxhole.vpn.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobeRouteGeometryAndroidTest {
    @Test fun unrevealedAndRearRoutesHaveNoVisibleStroke() {
        val route = GlobeRoute(GlobeCoordinates.from(0.0, -35.0), GlobeCoordinates.from(0.0, 35.0))
        val geometry = GlobeRouteGeometry(route)
        geometry.update(GlobeOrientation(0f, 0f), 0f)
        assertTrue(geometry.arc.isEmpty)
        geometry.update(GlobeOrientation(180f, 0f), 1f)
        assertTrue(geometry.arc.isEmpty)
        assertNull(geometry.device)
        assertNull(geometry.exit)
        assertNull(geometry.deviceMarker)
        assertNull(geometry.exitMarker)
    }

    @Test fun revealAndOcclusionKeepTheRouteInsideTheSphere() {
        val route = GlobeRoute(GlobeCoordinates.from(25.0, -100.0), GlobeCoordinates.from(-25.0, 45.0))
        val geometry = GlobeRouteGeometry(route)
        var visible = 0
        for (yaw in listOf(-90f, -30f, 0f, 30f, 90f)) {
            for (fraction in listOf(.01f, .25f, .5f, 1f)) {
                geometry.update(GlobeOrientation(yaw, 25f), fraction)
                if (!geometry.arc.isEmpty) {
                    visible++
                    val bounds = geometry.arc.getBounds()
                    assertTrue(bounds.left >= 54.99f && bounds.right <= 345.01f)
                    assertTrue(bounds.top >= 47.99f && bounds.bottom <= 338.01f)
                }
                geometry.deviceMarker?.let { assertEquals(geometry.device, it.center) }
                geometry.exitMarker?.let { assertEquals(geometry.exit, it.center) }
            }
        }
        assertTrue(visible >= 5)
    }

    @Test fun matchingGeoLocationsKeepTwoVisibleMarkersAndARevealedShortConnection() {
        val location = GlobeCoordinates.from(35.0, 10.0)
        val geometry = GlobeRouteGeometry(GlobeRoute(location, location))
        for (fraction in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            geometry.update(GlobeOrientation(-10f, 35f), fraction)
            assertTrue(geometry.arc.isEmpty)
        }
        geometry.update(GlobeOrientation(-10f, 35f), .5f)
        assertTrue(!geometry.arc.isEmpty)
        assertTrue((requireNotNull(geometry.device) - requireNotNull(geometry.exit)).getDistance() >= 11f)
        val partialBounds = geometry.arc.getBounds()
        geometry.update(GlobeOrientation(-10f, 35f), 1f)
        assertTrue(geometry.arc.getBounds().width > partialBounds.width)
        geometry.update(GlobeOrientation(170f, -35f), 1f)
        assertTrue(geometry.arc.isEmpty)
        assertNull(geometry.device)
        assertNull(geometry.exit)
    }
}
