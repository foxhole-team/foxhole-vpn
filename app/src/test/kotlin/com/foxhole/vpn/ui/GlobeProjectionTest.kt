package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GlobeProjectionTest {
    @Test
    fun rotationWrapsLongitudeAndStopsAtThePoles() {
        assertEquals(GlobeOrientation(-170f, 90f), GlobeOrientation(170f, 80f).turn(20f, 30f))
        assertEquals(GlobeOrientation(170f, -90f), GlobeOrientation(-170f, -80f).turn(-20f, -30f))
        val initial = GlobeOrientation()
        assertEquals(initial, initial.turn(Float.NaN, 0f))
        assertEquals(initial, initial.turn(0f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun yawAndPitchRotateTheSphereAroundDistinctAxes() {
        val front = floatArrayOf(0f, 0f, 1f)
        val right = GlobeProjection(GlobeOrientation(90f, 0f)).rotate(front)
        assertEquals(1f, right[0], 0.00001f)
        assertEquals(0f, right[1], 0.00001f)
        assertEquals(0f, right[2], 0.00001f)
        val down = GlobeProjection(GlobeOrientation(0f, 90f)).rotate(front)
        assertEquals(0f, down[0], 0.00001f)
        assertEquals(-1f, down[1], 0.00001f)
        assertEquals(0f, down[2], 0.00001f)
    }

    @Test
    fun frontAndRearHemispheresStayDistinctAcrossTheDateLine() {
        val vertices = GlobeProjection.spherePoint(179f, 0f) + GlobeProjection.spherePoint(-179f, 0f)
        val rotated = GlobeProjection(GlobeOrientation(180f, 0f)).rotate(vertices)
        assertTrue(rotated[2] > .99f)
        assertTrue(rotated[5] > .99f)
        assertTrue(kotlin.math.abs(rotated[0] - rotated[3]) < .04f)
    }

    @Test
    fun rearTrianglesDisappearAndFrontTrianglesKeepTheirVertices() {
        val rear = floatArrayOf(-.1f, 0f, -.99f, .1f, 0f, -.99f, 0f, .1f, -.99f)
        val output = FloatArray(8)
        assertEquals(0, GlobeProjection.clipTriangle(rear, 0, 1, 2, output))
        val front = floatArrayOf(-.1f, 0f, .99f, .1f, 0f, .99f, 0f, .1f, .99f)
        assertEquals(3, GlobeProjection.clipTriangle(front, 0, 1, 2, output))
        assertEquals(-.1f, output[0], 0f)
        assertEquals(.1f, output[2], 0f)
        assertEquals(.1f, output[5], 0f)
    }

    @Test
    fun horizonCrossingsAreClippedToTheSphericalRim() {
        val vertices = floatArrayOf(.99f, 0f, .1f, .98f, .1f, .1f, .98f, -.1f, -.1f)
        val output = FloatArray(8)
        assertEquals(4, GlobeProjection.clipTriangle(vertices, 0, 1, 2, output))
        for (index in listOf(0, 6)) {
            val length = sqrt(output[index] * output[index] + output[index + 1] * output[index + 1])
            assertEquals(1f, length, 0.00001f)
        }
    }
}
