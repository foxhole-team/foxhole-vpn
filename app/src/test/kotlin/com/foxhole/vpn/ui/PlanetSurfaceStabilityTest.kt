package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PlanetSurfaceStabilityTest {
    @Test fun vectorLandOnlyBuffersKeepTheSameLandWithoutBuildingUnusedLayers() {
        val detailed = PlanetGeometryBuffers(fastUpdates = true)
        val vector = PlanetGeometryBuffers(fastUpdates = true, landOnly = true)
        for (orientation in listOf(GlobeOrientation(), GlobeOrientation(130f, 75f), GlobeOrientation(-160f, -55f))) {
            detailed.update(orientation)
            vector.update(orientation)
            assertEquals(detailed.landFloatCount, vector.landFloatCount)
            assertArrayEquals(detailed.landVertices.copyOf(detailed.landFloatCount), vector.landVertices.copyOf(vector.landFloatCount), 0f)
            assertEquals(0, vector.gridFloatCount)
            assertEquals(0, vector.coastFloatCount)
            assertEquals(0, vector.coastVertexFloatCount)
        }
    }

    @Test
    fun interactionAndSettledFramesKeepExactlyTheSameLandCoastAndGrid() {
        val settled = PlanetGeometryBuffers(fastUpdates = false)
        val interaction = PlanetGeometryBuffers(fastUpdates = true)
        for (yaw in -180..180 step 30) {
            for (pitch in listOf(-90f, -65f, -20f, 0f, 30f, 70f, 90f)) {
                val orientation = GlobeOrientation(yaw.toFloat(), pitch)
                settled.update(orientation)
                interaction.update(orientation)
                assertBatch("land at $orientation", settled.landVertices, settled.landFloatCount, interaction.landVertices, interaction.landFloatCount)
                assertBatch("coast at $orientation", settled.coastLines, settled.coastFloatCount, interaction.coastLines, interaction.coastFloatCount)
                assertBatch("grid at $orientation", settled.gridLines, settled.gridFloatCount, interaction.gridLines, interaction.gridFloatCount)
            }
        }
    }

    @Test
    fun majorIslandCoastRemainsPresentThroughFrontHemisphereRotation() {
        val interaction = PlanetGeometryBuffers(fastUpdates = true)
        val settled = PlanetGeometryBuffers(fastUpdates = false)
        for ((longitude, latitude) in listOf(-2.5f to 54f, 138.3f to 36.2f, 80.7f to 7.5f)) {
            val location = GlobeProjection.spherePoint(longitude, latitude)
            for (offset in listOf(-75f, -45f, 0f, 45f, 75f)) {
                val orientation = GlobeOrientation(-longitude + offset, latitude)
                val point = GlobeProjection(orientation).rotate(location)
                assertTrue(point[2] > 0f)
                settled.update(orientation)
                interaction.update(orientation)
                val x = 200f + 145f * point[0]
                val y = 193f - 145f * point[1]
                assertTrue("Fixture has no source coast", hasNearbyCoast(settled, x, y))
                assertTrue("Island coast vanished during interaction at $longitude,$latitude offset=$offset", hasNearbyCoast(interaction, x, y))
            }
        }
    }

    @Test
    fun horizonCrossingDoesNotChangeGeometryWhenMotionSettles() {
        val settled = PlanetGeometryBuffers(fastUpdates = false)
        val interaction = PlanetGeometryBuffers(fastUpdates = true)
        for (quarterDegree in 320..400) {
            val orientation = GlobeOrientation(quarterDegree / 4f, 0f)
            settled.update(orientation)
            interaction.update(orientation)
            assertBatch("limb land", settled.landVertices, settled.landFloatCount, interaction.landVertices, interaction.landFloatCount)
            assertBatch("limb coast", settled.coastLines, settled.coastFloatCount, interaction.coastLines, interaction.coastFloatCount)
        }
    }

    @Test
    fun legacyContoursPreserveTheSameCoastWithoutBuildingAnUnusedStrokeMesh() {
        val legacy = PlanetGeometryBuffers(fastUpdates = true, meshCoasts = false)
        val native = PlanetGeometryBuffers(fastUpdates = false)
        for (yaw in -180..180 step 30) {
            val orientation = GlobeOrientation(yaw.toFloat(), 35f)
            legacy.update(orientation)
            native.update(orientation)
            assertBatch("legacy coast", native.coastLines, native.coastFloatCount, legacy.coastLines, legacy.coastFloatCount)
            assertEquals(0, legacy.coastVertexFloatCount)
            assertTrue(legacy.coastVertices.isEmpty())
            assertTrue(native.coastVertexFloatCount > 0)
        }
    }

    private fun assertBatch(label: String, expected: FloatArray, expectedCount: Int, actual: FloatArray, actualCount: Int) {
        assertEquals("$label length", expectedCount, actualCount)
        assertArrayEquals(label, expected.copyOf(expectedCount), actual.copyOf(actualCount), 0f)
    }

    private fun hasNearbyCoast(buffers: PlanetGeometryBuffers, x: Float, y: Float): Boolean {
        for (index in 0 until buffers.coastFloatCount step 2) {
            if (hypot(buffers.coastLines[index] - x, buffers.coastLines[index + 1] - y) < 3f) return true
        }
        return false
    }
}
