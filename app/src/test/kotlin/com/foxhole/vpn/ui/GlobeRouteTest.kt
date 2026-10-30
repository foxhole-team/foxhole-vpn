package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GlobeRouteTest {
    @Test
    fun missingAndInvalidCoordinatesNeverProduceMarkers() {
        for ((latitude, longitude) in listOf(
            null to null, 0.0 to null, null to 0.0, 91.0 to 0.0,
            0.0 to -181.0, Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY,
        )) assertNull(GlobeCoordinates.from(latitude, longitude))
        assertNotNull(GlobeCoordinates.from(90.0, 180.0))
        val empty = GlobeRoute(null, null)
        assertNull(empty.device)
        assertNull(empty.exit)
        assertTrue(empty.arc.isEmpty())
    }

    @Test
    fun oneKnownLocationHasOneMarkerAndNoInventedRoute() {
        val location = GlobeCoordinates.from(0.0, 0.0)
        val deviceOnly = GlobeRoute(location, null)
        assertNotNull(deviceOnly.device)
        assertNull(deviceOnly.exit)
        assertTrue(deviceOnly.arc.isEmpty())
        val exitOnly = GlobeRoute(null, location)
        assertNull(exitOnly.device)
        assertNotNull(exitOnly.exit)
        assertTrue(exitOnly.arc.isEmpty())
    }

    @Test
    fun equalLocationsDoNotCreateAnUnstableZeroLengthArc() {
        val location = GlobeProjection.spherePoint(52f, 13f)
        assertTrue(greatCircleArc(location, location).isEmpty())
    }

    @Test
    fun dateLineArcTakesTheShortPathAndStaysOnTheSphere() {
        val route = GlobeRoute(GlobeCoordinates.from(0.0, 179.0), GlobeCoordinates.from(0.0, -179.0))
        assertTrue(route.arc.isNotEmpty())
        val rotated = GlobeProjection(GlobeOrientation(180f, 0f)).rotate(route.arc)
        for (index in rotated.indices step 3) {
            assertTrue(rotated[index + 2] > .99f)
            assertUnitPoint(rotated, index)
        }
    }

    @Test
    fun antipodalAndNearlyAntipodalArcsRemainFiniteAndEndAtTheKnownPoints() {
        for (longitude in listOf(180f, 179.9999f, -180f)) {
            val from = GlobeProjection.spherePoint(0f, 0f)
            val to = GlobeProjection.spherePoint(longitude, 0f)
            val arc = greatCircleArc(from, to)
            for (index in arc.indices step 3) assertUnitPoint(arc, index)
            for (axis in 0..2) {
                assertEquals(from[axis], arc[axis], 0f)
                assertEquals(to[axis], arc[arc.size - 3 + axis], 0f)
            }
        }
    }

    @Test
    fun aPointBehindTheRotatedGlobeIsHidden() {
        val point = GlobeProjection.spherePoint(0f, 0f)
        assertTrue(globePointVisible(point))
        val rear = GlobeProjection(GlobeOrientation(180f, 0f)).rotate(point)
        assertFalse(globePointVisible(rear))
        assertFalse(globePointVisible(floatArrayOf(Float.NaN, 0f, 1f)))
    }

    @Test
    fun arcSegmentsBehindTheGlobeAreSkippedAndCrossingsEndAtTheLimb() {
        val output = FloatArray(4)
        assertFalse(GlobeProjection.clipSegment(floatArrayOf(.9f, 0f, -.4f, .8f, 0f, -.6f), 0, 1, output))
        assertTrue(GlobeProjection.clipSegment(floatArrayOf(.9f, 0f, .4f, .9f, 0f, -.4f), 0, 1, output))
        assertEquals(.9f, output[0], 0f)
        assertEquals(1f, sqrt(output[2] * output[2] + output[3] * output[3]), 0.00001f)
    }

    @Test
    fun routeCenterFacesTheArcMidpointAcrossTheDateLineAndAtAntipodes() {
        for ((from, to) in listOf(
            GlobeCoordinates.from(0.0, 179.0) to GlobeCoordinates.from(0.0, -179.0),
            GlobeCoordinates.from(35.0, -75.0) to GlobeCoordinates.from(60.0, 100.0),
            GlobeCoordinates.from(0.0, 0.0) to GlobeCoordinates.from(0.0, 180.0),
        )) {
            val route = GlobeRoute(from, to)
            val middle = route.arc.copyOfRange(96, 99)
            val center = GlobeProjection(requireNotNull(route.center)).rotate(middle)
            assertEquals(0f, center[0], .0001f)
            assertEquals(0f, center[1], .0001f)
            assertEquals(1f, center[2], .0001f)
            val endpoints = GlobeProjection(requireNotNull(route.center)).rotate(requireNotNull(route.device) + requireNotNull(route.exit))
            assertEquals(endpoints[2], endpoints[5], .0001f)
            assertTrue(endpoints[2] >= -.0001f)
        }
    }

    @Test
    fun centeringUsesTheShortYawDirectionAndStopsAtTheTarget() {
        val start = GlobeOrientation(179f, 15f)
        val target = GlobeOrientation(-179f, 30f)
        assertEquals(-180f, start.toward(target, .5f).yaw, .0001f)
        assertEquals(target, start.toward(target, 1f))
        val coordinates = requireNotNull(GlobeCoordinates.from(-30.0, 100.0))
        val point = GlobeProjection(GlobeOrientation.centeredOn(coordinates)).rotate(coordinates.vector())
        assertEquals(1f, point[2], .0001f)
    }

    private fun assertUnitPoint(points: FloatArray, index: Int) {
        for (axis in 0..2) assertTrue(points[index + axis].isFinite())
        val length = sqrt((0..2).sumOf { points[index + it].toDouble() * points[index + it] })
        assertEquals(1.0, length, 0.00001)
    }
}
