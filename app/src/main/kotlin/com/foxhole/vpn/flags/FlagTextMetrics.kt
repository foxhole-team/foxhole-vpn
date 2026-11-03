package com.foxhole.vpn.flags

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

internal fun capitalLetterHeightPx(paint: Paint): Float {
    val bounds = Rect()
    paint.getTextBounds("H", 0, 1, bounds)
    if (bounds.height() <= 0) {
        Paint(paint).apply { typeface = Typeface.DEFAULT_BOLD }.getTextBounds("H", 0, 1, bounds)
    }
    return bounds.height().toFloat()
}
