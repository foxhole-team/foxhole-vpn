package com.foxhole.vpn.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

internal class GlobeAppearance(val light: Boolean = false, val monochrome: Boolean = false, landTint: Color? = null,
    deviceTint: Color? = null, serverTint: Color? = null) {
    val devicePoint = deviceTint ?: if (light) Color(0xFF7A7A7A) else Color(0xFFB5B5B5)
    val serverPoint = serverTint ?: if (light) Color(0xFF262626) else Color.White
    private val foreground = when { monochrome -> Color(0xFF6A6A6A); landTint != null -> landTint; light -> Color(0xFF262626); else -> Color(0xFFBDBDBD) }
    val land = foreground.copy(alpha = .84f)
    val landPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { color = land.toArgb() }
    val rim = foreground.copy(alpha = .2f)
    val routeCore = serverPoint.copy(alpha = .94f)
    val routeEdge = if (light && serverTint == null) Color.White else Color.Black
    val edgeGlow = Brush.radialGradient(
        0f to foreground.copy(alpha = 0f),
        .81f to foreground.copy(alpha = 0f),
        .86f to foreground.copy(alpha = .015f),
        .91f to foreground.copy(alpha = .045f),
        .95f to foreground.copy(alpha = .025f),
        1f to foreground.copy(alpha = 0f),
        center = PlanetCenter,
        radius = 160f,
    )
    private val shade = if (light) .14f else .24f
    val edgeShade = Brush.radialGradient(
        0f to Color.Black.copy(alpha = 0f),
        .66f to Color.Black.copy(alpha = 0f),
        .76f to Color.Black.copy(alpha = .01f),
        .84f to Color.Black.copy(alpha = shade * .25f),
        .90f to Color.Black.copy(alpha = shade * .75f),
        .925f to Color.Black.copy(alpha = shade),
        .955f to Color.Black.copy(alpha = shade * .4f),
        1f to Color.Black.copy(alpha = 0f),
        center = PlanetCenter,
        radius = 160f,
    )
}

internal fun DrawScope.drawGlobeRoute(
    route: GlobeRouteGeometry,
    sphere: Path,
    appearance: GlobeAppearance,
    confirmed: Boolean,
    markerScale: Float,
) {
    clipPath(sphere) {
        if (confirmed) {
            drawPath(route.arc, appearance.routeEdge.copy(alpha = .88f), style = Stroke(2.8f * markerScale, cap = StrokeCap.Round))
            drawPath(route.arc, appearance.routeCore, style = Stroke(1.8f * markerScale, cap = StrokeCap.Round))
        }
        route.deviceMarker?.let { drawGlobeMarker(it, appearance, server = false, markerScale = markerScale) }
        if (confirmed) route.exitMarker?.let { drawGlobeMarker(it, appearance, server = true, markerScale = markerScale) }
    }
}

private fun DrawScope.drawGlobeMarker(marker: GlobeSurfaceMarker, appearance: GlobeAppearance, server: Boolean, markerScale: Float) {
    withTransform({
        translate(marker.center.x, marker.center.y)
        rotate(marker.rotationDegrees, Offset.Zero)
        scale(markerScale * marker.radialScale, markerScale, Offset.Zero)
    }) {
        drawCircle((if (appearance.light) Color.Black else Color.White).copy(alpha = marker.opacity), 5.3f, Offset.Zero)
        drawCircle(appearance.routeEdge.copy(alpha = marker.opacity), 4.7f, Offset.Zero)
        val color = if (server) appearance.serverPoint else appearance.devicePoint
        drawCircle(color.copy(alpha = marker.opacity), 3.7f, Offset.Zero)
    }
}

internal fun DrawScope.drawPlanetGeography(geometry: PlanetGeometry, appearance: GlobeAppearance) {
    clipPath(geometry.sphere) {
        if (Build.VERSION.SDK_INT >= 29) {
            if (geometry.landFloatCount > 0) drawIntoCanvas {
                it.nativeCanvas.drawVertices(AndroidCanvas.VertexMode.TRIANGLES, geometry.landFloatCount, geometry.landVertices, 0,
                    null, 0, null, 0, null, 0, 0, appearance.landPaint)
            }
        } else drawPath(geometry.land, appearance.land)
    }
    drawCircle(appearance.rim, 145f, PlanetCenter, style = Stroke(.65f))
}

private val PlanetCenter = Offset(200f, 193f)

internal fun DrawScope.drawPlanetEdge(appearance: GlobeAppearance) {
    drawCircle(appearance.edgeShade, 160f, PlanetCenter)
    drawCircle(appearance.edgeGlow, 160f, PlanetCenter)
}
