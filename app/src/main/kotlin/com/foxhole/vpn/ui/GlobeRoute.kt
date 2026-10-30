package com.foxhole.vpn.ui

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@ConsistentCopyVisibility
internal data class GlobeCoordinates private constructor(val latitude: Double, val longitude: Double) {
    fun vector(): FloatArray = GlobeProjection.spherePoint(longitude.toFloat(), latitude.toFloat())

    companion object {
        fun from(latitude: Double?, longitude: Double?): GlobeCoordinates? {
            if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return GlobeCoordinates(latitude, longitude)
        }
    }
}

internal class GlobeRoute(device: GlobeCoordinates?, exit: GlobeCoordinates?) {
    val device = device?.vector()
    val exit = exit?.vector()
    val arc = if (this.device != null && this.exit != null) greatCircleArc(this.device, this.exit) else FloatArray(0)
    val center: GlobeOrientation? = when {
        arc.isNotEmpty() -> orientationAt(arc, arc.size / 6)
        this.exit != null -> orientationAt(this.exit, 0)
        this.device != null -> orientationAt(this.device, 0)
        else -> null
    }

    private fun orientationAt(points: FloatArray, point: Int): GlobeOrientation {
        val index = point * 3
        val longitude = Math.toDegrees(kotlin.math.atan2(points[index].toDouble(), points[index + 2].toDouble()))
        val latitude = Math.toDegrees(kotlin.math.asin(points[index + 1].toDouble().coerceIn(-1.0, 1.0)))
        return GlobeOrientation(-longitude.toFloat(), latitude.toFloat())
    }
}

internal fun greatCircleArc(from: FloatArray, to: FloatArray): FloatArray {
    require(from.size == 3 && to.size == 3)
    val dot = from.indices.sumOf { from[it].toDouble() * to[it] }.coerceIn(-1.0, 1.0)
    if (dot > 0.999999) return FloatArray(0)
    val angle = acos(dot)
    val tangent = DoubleArray(3) { to[it] - dot * from[it] }
    var length = sqrt(tangent.sumOf { it * it })
    if (length < 0.00001) {
        val axis = if (abs(from[1]) < .9f) 1 else 0
        for (index in tangent.indices) tangent[index] = (if (index == axis) 1.0 else 0.0) - from[index] * from[axis].toDouble()
        length = sqrt(tangent.sumOf { it * it })
    }
    for (index in tangent.indices) tangent[index] /= length
    val segments = 64
    return FloatArray((segments + 1) * 3).also { points ->
        for (step in 0..segments) {
            val theta = angle * step / segments
            for (axis in 0..2) {
                points[step * 3 + axis] = when (step) {
                    0 -> from[axis]
                    segments -> to[axis]
                    else -> (from[axis] * cos(theta) + tangent[axis] * sin(theta)).toFloat()
                }
            }
        }
    }
}

internal fun globePointVisible(point: FloatArray): Boolean = point.size == 3 && point.all { it.isFinite() } && point[2] >= 0f
