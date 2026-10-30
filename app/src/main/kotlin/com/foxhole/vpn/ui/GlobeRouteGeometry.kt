package com.foxhole.vpn.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.hypot

internal class GlobeRouteGeometry(private val route: GlobeRoute) {
    val arc = Path()
    var device: Offset? = null
        private set
    var exit: Offset? = null
        private set
    var deviceMarker: GlobeSurfaceMarker? = null
        private set
    var exitMarker: GlobeSurfaceMarker? = null
        private set
    private val rotatedArc = FloatArray(route.arc.size)
    private val point = FloatArray(3)
    private val segment = FloatArray(4)
    private val partial = FloatArray(6)
    private var orientation: GlobeOrientation? = null
    private var reveal = Float.NaN

    fun update(current: GlobeOrientation, revealFraction: Float = 1f) {
        val fraction = revealFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (orientation == current && reveal == fraction) return
        orientation = current
        reveal = fraction
        val projection = GlobeProjection(current)
        deviceMarker = projectPoint(route.device, projection)
        exitMarker = projectPoint(route.exit, projection)
        val home = deviceMarker
        val server = exitMarker
        // GeoIP can place both endpoints in one city; separation is presentation-only.
        if (home != null && server != null && (home.center - server.center).getDistance() < 12f) {
            val radial = home.center - Offset(200f, 193f)
            val length = radial.getDistance()
            val tangent = if (length > 1f) Offset(-radial.y / length, radial.x / length) else Offset(1f, 0f)
            val shifted = server.center + tangent * 12f
            val vector = shifted - Offset(200f, 193f)
            val radius = vector.getDistance()
            exitMarker = server.copy(center = if (radius > 138f) Offset(200f, 193f) + vector * (138f / radius) else shifted)
        }
        device = deviceMarker?.center
        exit = exitMarker?.center
        projection.rotate(route.arc, rotatedArc)
        arc.reset()
        val homePoint = device
        val exitPoint = exit
        if (homePoint != null && exitPoint != null && home != null && server != null &&
            (home.center - server.center).getDistance() < 12f
        ) {
            if (fraction == 0f) return
            val delta = exitPoint - homePoint
            val length = hypot(delta.x, delta.y).coerceAtLeast(.001f)
            val candidate = (homePoint + exitPoint) * .5f + Offset(-delta.y / length, delta.x / length) * 8f
            val controlOffset = candidate - Offset(200f, 193f)
            val controlRadius = controlOffset.getDistance()
            val control = if (controlRadius > 138f) Offset(200f, 193f) + controlOffset * (138f / controlRadius) else candidate
            arc.moveTo(homePoint.x, homePoint.y)
            val steps = 16
            for (step in 1..steps) {
                val t = step / steps.toFloat() * fraction
                val point = homePoint * ((1f - t) * (1f - t)) + control * (2f * (1f - t) * t) + exitPoint * (t * t)
                arc.lineTo(point.x, point.y)
            }
            return
        }
        val segments = (rotatedArc.size / 3 - 1).coerceAtLeast(0)
        val end = segments * fraction
        val complete = end.toInt()
        for (index in 0 until complete) {
            if (GlobeProjection.clipSegment(rotatedArc, index, index + 1, segment)) {
                appendSegment()
            }
        }
        val remainder = end - complete
        if (complete < segments && remainder > 0f) {
            var lengthSquared = 0f
            for (axis in 0..2) {
                val index = complete * 3 + axis
                partial[axis] = rotatedArc[index]
                partial[axis + 3] = rotatedArc[index] + (rotatedArc[index + 3] - rotatedArc[index]) * remainder
                lengthSquared += partial[axis + 3] * partial[axis + 3]
            }
            val length = sqrt(lengthSquared).coerceAtLeast(.000001f)
            for (axis in 3..5) partial[axis] /= length
            if (GlobeProjection.clipSegment(partial, 0, 1, segment)) appendSegment()
        }
    }

    private fun appendSegment() {
        arc.moveTo(screenX(segment[0]), screenY(segment[1]))
        arc.lineTo(screenX(segment[2]), screenY(segment[3]))
    }

    private fun projectPoint(source: FloatArray?, projection: GlobeProjection): GlobeSurfaceMarker? {
        if (source == null) return null
        projection.rotate(source, point)
        return globeSurfaceMarker(point)
    }

    private fun screenX(value: Float) = 200f + 145f * value
    private fun screenY(value: Float) = 193f - 145f * value
}

internal data class GlobeSurfaceMarker(val center: Offset, val radialScale: Float, val rotationDegrees: Float, val opacity: Float)

internal fun globeSurfaceMarker(point: FloatArray): GlobeSurfaceMarker? {
    if (point.size != 3 || point.any { !it.isFinite() } || point[2] <= 0f) return null
    val depth = point[2].coerceIn(0f, 1f)
    val visibility = (depth / .18f).coerceIn(0f, 1f)
    return GlobeSurfaceMarker(
        Offset(200f + 145f * point[0], 193f - 145f * point[1]),
        depth,
        Math.toDegrees(atan2(-point[1].toDouble(), point[0].toDouble())).toFloat(),
        visibility * visibility * (3f - 2f * visibility),
    )
}
