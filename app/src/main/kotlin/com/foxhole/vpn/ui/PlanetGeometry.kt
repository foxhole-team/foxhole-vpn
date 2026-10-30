package com.foxhole.vpn.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path

internal class PlanetGeometry(legacyPaths: Boolean = false, fastUpdates: Boolean = false, landOnly: Boolean = false) {
    val sphere = Path().apply { addOval(Rect(55f, 48f, 345f, 338f)) }
    private val buffers = PlanetGeometryBuffers(fastUpdates, meshCoasts = !legacyPaths, landOnly = landOnly)
    private val legacyLand = if (legacyPaths) Path() else null
    private val legacyCoast = if (legacyPaths && !landOnly) Path() else null

    val landVertices: FloatArray get() = buffers.landVertices
    val landFloatCount: Int get() = buffers.landFloatCount
    val gridLines: FloatArray get() = buffers.gridLines
    val gridFloatCount: Int get() = buffers.gridFloatCount
    val coastLines: FloatArray get() = buffers.coastLines
    val coastFloatCount: Int get() = buffers.coastFloatCount
    val coastVertices: FloatArray get() = buffers.coastVertices
    val coastVertexFloatCount: Int get() = buffers.coastVertexFloatCount
    val land: Path get() = checkNotNull(legacyLand) { "Land paths require legacyPaths" }
    val coast: Path get() = checkNotNull(legacyCoast) { "Coast paths require legacyPaths" }

    fun update(current: GlobeOrientation) {
        if (!buffers.update(current)) return
        legacyLand?.let { path ->
            path.reset()
            var index = 0
            while (index < landFloatCount) {
                path.moveTo(landVertices[index], landVertices[index + 1])
                path.lineTo(landVertices[index + 2], landVertices[index + 3])
                path.lineTo(landVertices[index + 4], landVertices[index + 5])
                path.close()
                index += 6
            }
        }
        legacyCoast?.let { path ->
            path.reset()
            var index = 0
            while (index < coastFloatCount) {
                path.moveTo(coastLines[index], coastLines[index + 1])
                path.lineTo(coastLines[index + 2], coastLines[index + 3])
                index += 4
            }
        }
    }
}
