package com.foxhole.vpn.ui

import kotlin.math.hypot

internal fun globeStrokeMesh(lines: FloatArray, count: Int, width: Float, vertices: FloatArray): Int {
    require(count in 0..lines.size && count % 4 == 0)
    require(width.isFinite() && width > 0f && vertices.size >= count * 3)
    var output = 0
    var index = 0
    while (index < count) {
        val x = lines[index]
        val y = lines[index + 1]
        val endX = lines[index + 2]
        val endY = lines[index + 3]
        val dx = endX - x
        val dy = endY - y
        val length = hypot(dx, dy)
        if (length.isFinite() && length > 0f) {
            val nx = -dy * (width * .5f / length)
            val ny = dx * (width * .5f / length)
            vertices[output++] = x + nx
            vertices[output++] = y + ny
            vertices[output++] = x - nx
            vertices[output++] = y - ny
            vertices[output++] = endX + nx
            vertices[output++] = endY + ny
            vertices[output++] = endX + nx
            vertices[output++] = endY + ny
            vertices[output++] = x - nx
            vertices[output++] = y - ny
            vertices[output++] = endX - nx
            vertices[output++] = endY - ny
        }
        index += 4
    }
    return output
}
