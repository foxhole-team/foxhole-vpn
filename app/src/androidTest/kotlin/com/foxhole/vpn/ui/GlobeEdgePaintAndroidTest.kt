package com.foxhole.vpn.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobeEdgePaintAndroidTest {
    @Test fun edgeFadesContinuouslyWithoutChangingTheCenterOrDistantBackground() {
        for (light in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(1600, 1640, Bitmap.Config.ARGB_8888)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(1600f, 1640f)) {
                drawRect(Color(0xFFC8C8C8))
                withTransform({ scale(4f, 4f, Offset.Zero) }) {
                    drawPlanetEdge(GlobeAppearance(light))
                }
            }
            val samples = (400..648).map { radius -> bitmap.getPixel(800 + radius, 772) and 255 }
            assertTrue("soft shading must remain visible", samples.min() <= 185)
            // A short neighborhood distinguishes a hard band from per-pixel color quantization.
            val averaged = samples.windowed(3).map { it.average() }
            val jumps = averaged.zipWithNext().mapIndexedNotNull { index, (a, b) ->
                if (kotlin.math.abs(a - b) > 3.0) "${index + 400}:$a->$b" else null
            }
            assertTrue("hard translucent bands (light=$light): $jumps", jumps.isEmpty())
            assertEquals(200, bitmap.getPixel(800, 772) and 255)
            assertEquals(200, samples.last())
            bitmap.recycle()
        }
    }
}
