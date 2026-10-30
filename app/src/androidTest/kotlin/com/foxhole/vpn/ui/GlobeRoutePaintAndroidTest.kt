package com.foxhole.vpn.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobeRoutePaintAndroidTest {
    @Test fun aConnectedRouteStaysVisibleAgainstGrayLandInBothThemes() {
        for (theme in VpnTheme.entries) {
            val light = theme == VpnTheme.LIGHT
            val bitmap = Bitmap.createBitmap(400, 410, Bitmap.Config.ARGB_8888)
            val background = if (light) Color(0xFF262626) else Color(0xFFA1A1A1)
            val route = GlobeRouteGeometry(GlobeRoute(GlobeCoordinates.from(0.0, -35.0), GlobeCoordinates.from(0.0, 35.0)))
            route.update(GlobeOrientation(0f, 0f))
            val sphere = PlanetGeometry().sphere
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(400f, 410f)) {
                drawRect(background)
                drawGlobeRoute(route, sphere, GlobeAppearance(light, theme == VpnTheme.NIGHT,
                    deviceTint = vpnGlobeDeviceTint(theme), serverTint = vpnGlobeServerTint(theme)), confirmed = true, markerScale = 1f)
            }
            val base = if (light) 38 else 161
            val strokePixels = (150..250).sumOf { x ->
                (189..197).count { y ->
                    val pixel = bitmap.getPixel(x, y)
                    val mean = ((pixel shr 16 and 255) + (pixel shr 8 and 255) + (pixel and 255)) / 3
                    kotlin.math.abs(mean - base) >= 50
                }
            }
            assertTrue("the route needs a continuous contrasting edge against gray land", strokePixels >= 100)
            bitmap.recycle()
        }
    }
}
