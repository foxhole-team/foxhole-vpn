package com.foxhole.vpn.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.res.ResourcesCompat
import com.foxhole.vpn.R
import com.foxhole.vpn.flags.capitalLetterHeightPx
import com.foxhole.vpn.flags.countryFlagBitmap
import com.foxhole.vpn.flags.countryFlagSizePx
import kotlin.math.roundToInt

internal fun widgetFlagBitmap(context: Context, countryCode: String, compact: Boolean): Bitmap? {
    val source = countryFlagBitmap(context, countryCode) ?: return null
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)
        textSize = context.resources.getDimension(if (compact) R.dimen.widget_compact_detail_text_size
            else R.dimen.widget_detail_text_size)
    }
    val size = countryFlagSizePx(capitalLetterHeightPx(paint), source.width, source.height) ?: return null
    val width = size.width.roundToInt().coerceAtLeast(1)
    val height = size.height.roundToInt().coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        density = context.resources.displayMetrics.densityDpi
        Canvas(this).drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
