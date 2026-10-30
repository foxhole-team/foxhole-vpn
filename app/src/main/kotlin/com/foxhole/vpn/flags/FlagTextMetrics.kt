package com.foxhole.vpn.flags

import android.graphics.Paint
import android.graphics.Rect

internal fun capitalLetterHeightPx(paint: Paint): Float {
    val bounds = Rect()
    paint.getTextBounds("H", 0, 1, bounds)
    return bounds.height().toFloat()
}
