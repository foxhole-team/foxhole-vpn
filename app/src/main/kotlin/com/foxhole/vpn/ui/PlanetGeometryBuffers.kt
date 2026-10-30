package com.foxhole.vpn.ui

import kotlin.math.cos
import kotlin.math.sin

internal class PlanetGeometryBuffers(
    private val sourceVertices: FloatArray = PlanetMeshData.vertices,
    private val sourceTriangles: IntArray = PlanetMeshData.triangles,
    private val sourceGrid: FloatArray = SphereGridVertices,
    private val sourceCoastEdges: IntArray = IntArray(0),
    private val optimizedClipping: Boolean = false,
    private val meshCoasts: Boolean = true,
) {
    constructor(fastUpdates: Boolean, meshCoasts: Boolean = true, landOnly: Boolean = false) : this(
        sourceGrid = if (landOnly) FloatArray(0) else SphereGridVertices,
        sourceCoastEdges = if (landOnly) IntArray(0) else PlanetCoastData.edges,
        optimizedClipping = fastUpdates,
        meshCoasts = meshCoasts,
    )

    // Horizon clipping can turn each triangle into two.
    val landVertices = FloatArray(sourceTriangles.size * 4)
    var landFloatCount: Int = 0
        private set
    val gridLines = FloatArray(sourceGrid.size / 6 * 4)
    var gridFloatCount: Int = 0
        private set
    val coastLines = FloatArray(sourceCoastEdges.size * 2)
    var coastFloatCount: Int = 0
        private set
    val coastVertices = if (meshCoasts) FloatArray(coastLines.size * 3) else FloatArray(0)
    var coastVertexFloatCount: Int = 0
        private set
    private val rotated = FloatArray(sourceVertices.size)
    private val rotatedGrid = FloatArray(sourceGrid.size)
    private val polygon = FloatArray(8)
    private val segment = FloatArray(4)
    private var yaw = Float.NaN
    private var pitch = Float.NaN

    init {
        require(sourceVertices.size % 3 == 0 && sourceTriangles.size % 3 == 0)
        require(sourceGrid.size % 6 == 0)
        require(sourceCoastEdges.size % 2 == 0)
    }

    fun update(current: GlobeOrientation): Boolean {
        if (!current.yaw.isFinite() || !current.pitch.isFinite()) return false
        if (yaw == current.yaw && pitch == current.pitch) return false
        yaw = current.yaw
        pitch = current.pitch
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(pitch.toDouble())
        val sinYaw = sin(yawRadians).toFloat()
        val cosYaw = cos(yawRadians).toFloat()
        val sinPitch = sin(pitchRadians).toFloat()
        val cosPitch = cos(pitchRadians).toFloat()
        rotate(sourceVertices, rotated, sinYaw, cosYaw, sinPitch, cosPitch)
        landFloatCount = 0
        var index = 0
        while (index < sourceTriangles.size) {
            if (optimizedClipping) {
                val a = sourceTriangles[index] * 3
                val b = sourceTriangles[index + 1] * 3
                val c = sourceTriangles[index + 2] * 3
                val az = rotated[a + 2]
                val bz = rotated[b + 2]
                val cz = rotated[c + 2]
                if (az >= 0f && bz >= 0f && cz >= 0f) {
                    appendRotatedLandPoint(a)
                    appendRotatedLandPoint(b)
                    appendRotatedLandPoint(c)
                    index += 3
                    continue
                }
                if (az < 0f && bz < 0f && cz < 0f) {
                    index += 3
                    continue
                }
            }
            val count = GlobeProjection.clipTriangle(
                rotated, sourceTriangles[index], sourceTriangles[index + 1], sourceTriangles[index + 2], polygon,
            )
            var corner = 1
            while (corner + 1 < count) {
                appendLandPoint(0)
                appendLandPoint(corner)
                appendLandPoint(corner + 1)
                corner++
            }
            index += 3
        }
        coastFloatCount = 0
        index = 0
        while (index < sourceCoastEdges.size) {
            if (clipSegment(rotated, sourceCoastEdges[index], sourceCoastEdges[index + 1])) {
                coastLines[coastFloatCount++] = screenX(segment[0])
                coastLines[coastFloatCount++] = screenY(segment[1])
                coastLines[coastFloatCount++] = screenX(segment[2])
                coastLines[coastFloatCount++] = screenY(segment[3])
            }
            index += 2
        }
        coastVertexFloatCount = if (meshCoasts) globeStrokeMesh(coastLines, coastFloatCount, .55f, coastVertices) else 0
        rotate(sourceGrid, rotatedGrid, sinYaw, cosYaw, sinPitch, cosPitch)
        gridFloatCount = 0
        index = 0
        while (index < rotatedGrid.size) {
            if (clipSegment(rotatedGrid, index / 3, index / 3 + 1)) {
                gridLines[gridFloatCount++] = screenX(segment[0])
                gridLines[gridFloatCount++] = screenY(segment[1])
                gridLines[gridFloatCount++] = screenX(segment[2])
                gridLines[gridFloatCount++] = screenY(segment[3])
            }
            index += 6
        }
        return true
    }

    private fun appendLandPoint(corner: Int) {
        landVertices[landFloatCount++] = screenX(polygon[corner * 2])
        landVertices[landFloatCount++] = screenY(polygon[corner * 2 + 1])
    }

    private fun appendRotatedLandPoint(index: Int) {
        landVertices[landFloatCount++] = screenX(rotated[index])
        landVertices[landFloatCount++] = screenY(rotated[index + 1])
    }

    private fun clipSegment(vertices: FloatArray, from: Int, to: Int): Boolean {
        if (optimizedClipping) {
            val a = from * 3
            val b = to * 3
            val az = vertices[a + 2]
            val bz = vertices[b + 2]
            if (az < 0f && bz < 0f) return false
            if (az >= 0f && bz >= 0f) {
                segment[0] = vertices[a]
                segment[1] = vertices[a + 1]
                segment[2] = vertices[b]
                segment[3] = vertices[b + 1]
                return true
            }
        }
        return GlobeProjection.clipSegment(vertices, from, to, segment)
    }

    private fun rotate(
        source: FloatArray,
        result: FloatArray,
        sinYaw: Float,
        cosYaw: Float,
        sinPitch: Float,
        cosPitch: Float,
    ) {
        var index = 0
        while (index < source.size) {
            val x = source[index]
            val y = source[index + 1]
            val z = source[index + 2]
            val rotatedZ = cosYaw * z - sinYaw * x
            result[index] = cosYaw * x + sinYaw * z
            result[index + 1] = cosPitch * y - sinPitch * rotatedZ
            result[index + 2] = sinPitch * y + cosPitch * rotatedZ
            index += 3
        }
    }

    private fun screenX(value: Float): Float = 200f + 145f * value
    private fun screenY(value: Float): Float = 193f - 145f * value
}

private val SphereGridVertices: FloatArray = FloatArray((12 * 60 + 5 * 120) * 6).also { result ->
    var index = 0
    fun point(longitude: Int, latitude: Int) {
        val lon = Math.toRadians(longitude.toDouble())
        val lat = Math.toRadians(latitude.toDouble())
        result[index++] = (cos(lat) * sin(lon)).toFloat()
        result[index++] = sin(lat).toFloat()
        result[index++] = (cos(lat) * cos(lon)).toFloat()
    }
    for (longitude in -180 until 180 step 30) {
        for (latitude in -90 until 90 step 3) {
            point(longitude, latitude)
            point(longitude, latitude + 3)
        }
    }
    for (latitude in -60..60 step 30) {
        for (longitude in -180 until 180 step 3) {
            point(longitude, latitude)
            point(longitude + 3, latitude)
        }
    }
}
