package com.foxhole.vpn.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnRoutingMode
import com.foxhole.vpn.vpnScenarioStatusIcon
import kotlin.math.ceil

internal fun widgetScenarioBitmap(context: Context, mode: VpnRoutingMode,
    color: Int, arabic: Boolean, label: String): Bitmap {
    val metrics = context.resources.displayMetrics
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (arabic) Typeface.SANS_SERIF else
            ResourcesCompat.getFont(context, R.font.tiny5_regular)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, metrics)
    }
    val capital = label.firstOrNull { it.isUpperCase() } ?: label.firstOrNull { it.isLetter() } ?: 'H'
    val bounds = Rect().also { paint.getTextBounds(capital.toString(), 0, 1, it) }
    val cap = bounds.height().coerceAtLeast(1)
    val font = paint.fontMetrics
    val baseline = ceil(-font.ascent).toInt()
    val glyph = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    ContextCompat.getDrawable(context, vpnScenarioStatusIcon(mode))?.mutate()?.apply {
        setTint(color)
        setBounds(0, 0, 96, 96)
        draw(Canvas(glyph))
    }
    val pixels = IntArray(96 * 96)
    glyph.getPixels(pixels, 0, 96, 0, 0, 96, 96)
    var left = 96; var top = 96; var right = -1; var bottom = -1
    pixels.forEachIndexed { index, value ->
        if (android.graphics.Color.alpha(value) > 16) {
            left = minOf(left, index % 96); right = maxOf(right, index % 96)
            top = minOf(top, index / 96); bottom = maxOf(bottom, index / 96)
        }
    }
    val ink = if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else Rect(0, 0, 96, 96)
    val width = ceil(cap.toFloat() * ink.width() / ink.height()).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, ceil(font.descent - font.ascent).toInt()
        .coerceAtLeast(cap), Bitmap.Config.ARGB_8888)
    bitmap.density = metrics.densityDpi
    val topOffset = (baseline - cap).coerceAtLeast(0)
    Canvas(bitmap).drawBitmap(glyph, ink, Rect(0, topOffset, width, topOffset + cap), Paint(Paint.ANTI_ALIAS_FLAG))
    glyph.recycle()
    return bitmap
}
