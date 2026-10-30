package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeStrokeMeshTest {
    @Test fun strokeKeepsBothEndpointsAndWidthWithoutPerSegmentDrawing() {
        val vertices = FloatArray(12)
        assertEquals(12, globeStrokeMesh(floatArrayOf(0f, 0f, 4f, 0f), 4, .5f, vertices))
        assertEquals(0f, vertices.minOfIndexedCoordinate(0), 0f)
        assertEquals(4f, vertices.maxOfIndexedCoordinate(0), 0f)
        assertEquals(-.25f, vertices.minOfIndexedCoordinate(1), 0f)
        assertEquals(.25f, vertices.maxOfIndexedCoordinate(1), 0f)
    }

    @Test fun collapsedHorizonSegmentsDoNotCreateNaNOrConsumeTriangles() {
        val vertices = FloatArray(24)
        val count = globeStrokeMesh(floatArrayOf(2f, 2f, 2f, 2f, 2f, 2f, 2.001f, 2.001f), 8, .55f, vertices)
        assertEquals(12, count)
        assertTrue(vertices.take(count).all { it.isFinite() })
        assertEquals(0, globeStrokeMesh(FloatArray(0), 0, .55f, vertices))
    }

    @Test fun everyCanonicalVisibleCoastSegmentRemainsInTheBatchedStrokeMesh() {
        val buffers = PlanetGeometryBuffers(fastUpdates = true)
        for (yaw in -180..180 step 45) {
            buffers.update(GlobeOrientation(yaw.toFloat(), 20f))
            assertTrue(buffers.coastFloatCount > 0)
            assertEquals(buffers.coastFloatCount * 3, buffers.coastVertexFloatCount)
            assertTrue(buffers.coastVertices.take(buffers.coastVertexFloatCount).all { it.isFinite() })
        }
    }

    private fun FloatArray.minOfIndexedCoordinate(axis: Int) = indices.filter { it % 2 == axis }.minOf { this[it] }
    private fun FloatArray.maxOfIndexedCoordinate(axis: Int) = indices.filter { it % 2 == axis }.maxOf { this[it] }
}
