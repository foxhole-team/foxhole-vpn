package com.foxhole.vpn.widget

import kotlin.math.roundToInt

internal fun widgetStatusActionInsets(widthDp: Int, density: Float): Pair<Int, Int> {
    val outerPadding = 2 * (12 * density).roundToInt()
    val available = ((widthDp.coerceAtLeast(0) * density).roundToInt() - outerPadding).coerceAtLeast(0)
    val minimumWidth = ((240 * density).roundToInt() - outerPadding).coerceAtLeast(0)
    val totalInset = (available - minimumWidth).coerceAtLeast(0)
    val left = totalInset / 2
    return left to totalInset - left
}
