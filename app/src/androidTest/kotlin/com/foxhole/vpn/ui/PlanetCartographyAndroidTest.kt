package com.foxhole.vpn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanetCartographyAndroidTest {
    @Test fun legacyContoursAndNativeBatchesKeepTheSameGeographyAtRestAndInMotion() {
        val legacy = PlanetGeometry(legacyPaths = true, fastUpdates = true)
        val native = PlanetGeometry(legacyPaths = false)
        for (orientation in listOf(GlobeOrientation(), GlobeOrientation(130f, 45f), GlobeOrientation(-160f, -70f))) {
            legacy.update(orientation)
            native.update(orientation)
            assertEquals(native.landFloatCount, legacy.landFloatCount)
            assertEquals(native.coastFloatCount, legacy.coastFloatCount)
            assertEquals(0, legacy.coastVertexFloatCount)
            assertTrue(native.coastVertexFloatCount > 0)
            assertArrayEquals(native.landVertices.copyOf(native.landFloatCount), legacy.landVertices.copyOf(legacy.landFloatCount), 0f)
            assertArrayEquals(native.coastLines.copyOf(native.coastFloatCount), legacy.coastLines.copyOf(legacy.coastFloatCount), 0f)
            assertTrue(!legacy.land.isEmpty)
            assertTrue(!legacy.coast.isEmpty)
        }
    }

    @Test fun knownMajorIslandsHaveAVisibleLegacyContourThroughTheFrontHemisphere() {
        val legacy = PlanetGeometry(legacyPaths = true, fastUpdates = true)
        for ((longitude, latitude) in listOf(-2.5f to 54f, 138.3f to 36.2f, 80.7f to 7.5f)) {
            for (offset in listOf(-75f, 0f, 75f)) {
                val orientation = GlobeOrientation(-longitude + offset, latitude)
                val point = GlobeProjection(orientation).rotate(GlobeProjection.spherePoint(longitude, latitude))
                legacy.update(orientation)
                val x = 200f + 145f * point[0]
                val y = 193f - 145f * point[1]
                assertTrue(!legacy.coast.isEmpty)
                assertTrue((0 until legacy.coastFloatCount step 2).any { index ->
                    kotlin.math.hypot(legacy.coastLines[index] - x, legacy.coastLines[index + 1] - y) < 3f
                })
            }
        }
    }

    @Test fun bothVectorThemesKeepFlatNeutralGrayLandAndAThinRim() {
        for (light in listOf(false, true)) {
            val appearance = GlobeAppearance(light)
            val land = appearance.landPaint.color
            assertNeutralGray(land)
            assertNeutralGray(appearance.rim.toArgb())
            assertEquals(appearance.land.toArgb(), land)
            assertTrue(appearance.land.alpha > appearance.rim.alpha)
            assertNull(appearance.landPaint.shader)
        }
    }

    private fun assertNeutralGray(argb: Int) {
        val red = argb shr 16 and 255
        val green = argb shr 8 and 255
        val blue = argb and 255
        assertEquals(red, green)
        assertEquals(red, blue)
    }
}
