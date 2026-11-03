package com.foxhole.vpn.widget

import kotlin.math.roundToInt

internal fun widgetStatusActionInsets(widthDp: Int, density: Float): Pair<Int, Int> {
    val inset = ((widthDp.coerceAtLeast(0) - 300).coerceAtLeast(0) * .5f * density).roundToInt()
    return inset to inset
}

