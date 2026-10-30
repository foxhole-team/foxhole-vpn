package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PlanetGeometryBuffersTest {
    @Test
    fun majorIslandsVisibleAtRestRemainLandDuringRotation() {
        for ((longitude, latitude) in listOf(-2.5f to 54f, 138.3f to 36.2f, 80.7f to 7.5f)) {
            val orientation = GlobeOrientation(-longitude, latitude)
            val full = PlanetGeometryBuffers(sourceGrid = FloatArray(0))
            full.update(orientation)
            assertTrue("Full mesh must contain fixture $longitude,$latitude", containsCenter(full))
            val moving = PlanetGeometryBuffers(fastUpdates = true)
            moving.update(orientation)
            assertTrue("Major island disappeared during rotation: $longitude,$latitude", containsCenter(moving))
        }
    }

    private fun containsCenter(buffers: PlanetGeometryBuffers): Boolean {
        val points = buffers.landVertices
        for (index in 0 until buffers.landFloatCount step 6) {
            val crosses = (0..2).map { corner ->
                val a = index + corner * 2
                val b = index + (corner + 1) % 3 * 2
                (points[b] - points[a]) * (193f - points[a + 1]) -
                    (points[b + 1] - points[a + 1]) * (200f - points[a])
            }
            if (crosses.all { it >= 0f } || crosses.all { it <= 0f }) return true
        }
        return false
    }

    @Test
    fun canonicalMeshStaysWithinItsBudgetAndExplicitEmptyBatchesStayEmpty() {
        assertTrue(PlanetMeshData.vertexCount <= 7_000)
        assertTrue(PlanetMeshData.triangleCount <= 7_600)
        assertTrue(PlanetCoastData.edges.size / 2 <= 6_200)
        val buffers = PlanetGeometryBuffers(sourceGrid = FloatArray(0), optimizedClipping = true)
        for (yaw in -180..180 step 30) {
            buffers.update(GlobeOrientation(yaw.toFloat(), 15f))
            assertTrue(buffers.landFloatCount > 0)
            assertEquals(0, buffers.landFloatCount % 6)
            assertEquals(0, buffers.gridFloatCount)
            assertEquals(0, buffers.coastFloatCount)
            assertVisiblePoints(buffers.landVertices, buffers.landFloatCount)
        }
    }

    @Test
    fun tinyIslandTerrainCanBeOmittedWithoutLosingTheRealLocationMarker() {
        for ((longitude, latitude) in listOf(57.5f to -20.25f, 14.483573f to 35.876876f)) {
            val orientation = GlobeOrientation(-longitude, latitude)
            val geometry = PlanetGeometryBuffers(fastUpdates = true)
            geometry.update(orientation)
            assertFalse("Tiny island must be omitted by the fixed detail selection", containsCenter(geometry))
            val location = GlobeCoordinates.from(latitude.toDouble(), longitude.toDouble())
            assertNotNull(location)
            val route = GlobeRoute(location, null)
            assertNotNull(route.device)
            val marker = globeSurfaceMarker(GlobeProjection(orientation).rotate(requireNotNull(route.device)))
            assertNotNull(marker)
            assertEquals(200f, requireNotNull(marker).center.x, .001f)
            assertEquals(193f, marker.center.y, .001f)
        }
    }

    @Test
    fun frontTriangleProducesSixViewportFloatsAndRearTriangleProducesNone() {
        val vertices = floatArrayOf(-.1f, 0f, .99f, .1f, 0f, .99f, 0f, .1f, .99f)
        val buffers = PlanetGeometryBuffers(vertices, intArrayOf(0, 1, 2), FloatArray(0))
        assertTrue(buffers.update(GlobeOrientation(0f, 0f)))
        assertEquals(6, buffers.landFloatCount)
        assertEquals(185.5f, buffers.landVertices[0], .0001f)
        assertEquals(193f, buffers.landVertices[1], .0001f)
        buffers.update(GlobeOrientation(180f, 0f))
        assertEquals(0, buffers.landFloatCount)
    }

    @Test
    fun horizonClippedQuadBecomesTwoTrianglesWithoutExceedingCapacity() {
        val vertices = floatArrayOf(.99f, 0f, .1f, .98f, .1f, .1f, .98f, -.1f, -.1f)
        val buffers = PlanetGeometryBuffers(vertices, intArrayOf(0, 1, 2), FloatArray(0))
        buffers.update(GlobeOrientation(0f, 0f))
        assertEquals(12, buffers.landFloatCount)
        assertEquals(12, buffers.landVertices.size)
        assertVisiblePoints(buffers.landVertices, buffers.landFloatCount)
    }

    @Test
    fun gridBatchKeepsOnlyVisibleOrHorizonClippedSegments() {
        val grid = floatArrayOf(.9f, 0f, .1f, .9f, 0f, -.1f, -.1f, 0f, -.9f, .1f, 0f, -.9f)
        val buffers = PlanetGeometryBuffers(FloatArray(0), IntArray(0), grid)
        buffers.update(GlobeOrientation(0f, 0f))
        assertEquals(4, buffers.gridFloatCount)
        assertEquals(345f, buffers.gridLines[2], .0001f)
        assertVisiblePoints(buffers.gridLines, buffers.gridFloatCount)
    }

    @Test
    fun repeatedUpdatesReuseArraysAndUnchangedOrientationDoesNoWork() {
        val buffers = PlanetGeometryBuffers(sourceCoastEdges = PlanetCoastData.edges)
        val land = buffers.landVertices
        val grid = buffers.gridLines
        val coast = buffers.coastLines
        for (yaw in -180..180 step 30) {
            for (pitch in -90..90 step 30) {
                val orientation = GlobeOrientation(yaw.toFloat(), pitch.toFloat())
                buffers.update(orientation)
                assertSame(land, buffers.landVertices)
                assertSame(grid, buffers.gridLines)
                assertSame(coast, buffers.coastLines)
                assertEquals(0, buffers.landFloatCount % 6)
                assertEquals(0, buffers.gridFloatCount % 4)
                assertEquals(0, buffers.coastFloatCount % 4)
                assertTrue(buffers.landFloatCount in 0..land.size)
                assertTrue(buffers.gridFloatCount in 0..grid.size)
                assertTrue(buffers.coastFloatCount in 0..coast.size)
                assertVisiblePoints(land, buffers.landFloatCount)
                assertVisiblePoints(grid, buffers.gridFloatCount)
                assertVisiblePoints(coast, buffers.coastFloatCount)
                assertFalse(buffers.update(orientation))
            }
        }
    }

    @Test
    fun invalidOrientationDoesNotDiscardTheLastValidBuffers() {
        val buffers = PlanetGeometryBuffers()
        buffers.update(GlobeOrientation())
        val count = buffers.landFloatCount
        val first = buffers.landVertices[0]
        assertFalse(buffers.update(GlobeOrientation(Float.NaN, 0f)))
        assertEquals(count, buffers.landFloatCount)
        assertEquals(first, buffers.landVertices[0], 0f)
    }

    private fun assertVisiblePoints(vertices: FloatArray, count: Int) {
        var index = 0
        while (index < count) {
            val x = vertices[index]
            val y = vertices[index + 1]
            assertTrue(x.isFinite() && y.isFinite())
            assertTrue(hypot(x - 200f, y - 193f) <= 145.0001f)
            index += 2
        }
    }
}
