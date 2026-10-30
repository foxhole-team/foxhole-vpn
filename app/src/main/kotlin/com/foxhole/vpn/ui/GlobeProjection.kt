package com.foxhole.vpn.ui

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class GlobeOrientation(val yaw: Float = 18f, val pitch: Float = 15f) {
    fun toward(target: GlobeOrientation, fraction: Float): GlobeOrientation {
        val shortestYaw = ((target.yaw - yaw) % 360f + 540f) % 360f - 180f
        return turn(shortestYaw * fraction.coerceIn(0f, 1f), (target.pitch - pitch) * fraction.coerceIn(0f, 1f))
    }

    fun turn(horizontal: Float, vertical: Float): GlobeOrientation {
        if (!horizontal.isFinite() || !vertical.isFinite()) return this
        val longitude = ((yaw + horizontal) % 360f + 540f) % 360f - 180f
        return GlobeOrientation(longitude, (pitch + vertical).coerceIn(-90f, 90f))
    }

    companion object {
        fun centeredOn(coordinates: GlobeCoordinates): GlobeOrientation =
            GlobeOrientation(-coordinates.longitude.toFloat(), coordinates.latitude.toFloat())
    }
}

internal fun globeAngularVelocity(velocityPixelsPerSecond: Float, widthPixels: Int): Float {
    if (widthPixels <= 0 || !velocityPixelsPerSecond.isFinite()) return 0f
    val degreesPerSecond = (velocityPixelsPerSecond.toDouble() * 180.0 / widthPixels).toFloat()
    return degreesPerSecond.takeIf { it.isFinite() } ?: 0f
}

internal class GlobeProjection(orientation: GlobeOrientation) {
    private val yaw = Math.toRadians(orientation.yaw.toDouble())
    private val pitch = Math.toRadians(orientation.pitch.toDouble())
    private val sinYaw = sin(yaw).toFloat()
    private val cosYaw = cos(yaw).toFloat()
    private val sinPitch = sin(pitch).toFloat()
    private val cosPitch = cos(pitch).toFloat()

    fun rotate(vertices: FloatArray, result: FloatArray = FloatArray(vertices.size)): FloatArray {
        require(vertices.size % 3 == 0)
        require(result.size == vertices.size)
        return result.also {
            for (index in vertices.indices step 3) {
                val x = vertices[index]
                val y = vertices[index + 1]
                val z = vertices[index + 2]
                val rotatedZ = cosYaw * z - sinYaw * x
                result[index] = cosYaw * x + sinYaw * z
                result[index + 1] = cosPitch * y - sinPitch * rotatedZ
                result[index + 2] = sinPitch * y + cosPitch * rotatedZ
            }
        }
    }

    companion object {
        fun clipSegment(vertices: FloatArray, from: Int, to: Int, output: FloatArray): Boolean {
            require(output.size >= 4)
            val a = from * 3
            val b = to * 3
            val az = vertices[a + 2]
            val bz = vertices[b + 2]
            if (az < 0f && bz < 0f) return false
            output[0] = vertices[a]
            output[1] = vertices[a + 1]
            output[2] = vertices[b]
            output[3] = vertices[b + 1]
            if ((az < 0f) != (bz < 0f)) {
                val fraction = az / (az - bz)
                val x = output[0] + fraction * (output[2] - output[0])
                val y = output[1] + fraction * (output[3] - output[1])
                val length = sqrt(x * x + y * y).coerceAtLeast(0.000001f)
                val offset = if (az < 0f) 0 else 2
                output[offset] = x / length
                output[offset + 1] = y / length
            }
            return true
        }

        fun spherePoint(longitude: Float, latitude: Float): FloatArray {
            val lon = Math.toRadians(longitude.toDouble())
            val lat = Math.toRadians(latitude.toDouble())
            return floatArrayOf((cos(lat) * sin(lon)).toFloat(), sin(lat).toFloat(), (cos(lat) * cos(lon)).toFloat())
        }

        fun clipTriangle(vertices: FloatArray, a: Int, b: Int, c: Int, output: FloatArray): Int {
            require(output.size >= 8)
            var count = 0
            var previous = c * 3
            for (corner in 0..2) {
                val index = when (corner) { 0 -> a; 1 -> b; else -> c }
                val current = index * 3
                val previousVisible = vertices[previous + 2] >= 0f
                val currentVisible = vertices[current + 2] >= 0f
                if (previousVisible != currentVisible) {
                    val fraction = vertices[previous + 2] / (vertices[previous + 2] - vertices[current + 2])
                    val x = vertices[previous] + fraction * (vertices[current] - vertices[previous])
                    val y = vertices[previous + 1] + fraction * (vertices[current + 1] - vertices[previous + 1])
                    val length = sqrt(x * x + y * y).coerceAtLeast(0.000001f)
                    output[count * 2] = x / length
                    output[count * 2 + 1] = y / length
                    count++
                }
                if (currentVisible) {
                    output[count * 2] = vertices[current]
                    output[count * 2 + 1] = vertices[current + 1]
                    count++
                }
                previous = current
            }
            return count
        }
    }
}
