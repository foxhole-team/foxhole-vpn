package com.foxhole.vpn.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.foxhole.vpn.VpnRoutingMode
import com.foxhole.vpn.vpnScenarioStatusIcon

private data class ScenarioInk(val left: Float, val top: Float, val width: Float, val height: Float)

private fun scenarioInk(context: android.content.Context, resource: Int): ScenarioInk {
    val side = 96
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    return try {
        ContextCompat.getDrawable(context, resource)?.mutate()?.apply {
            setTint(android.graphics.Color.WHITE)
            setBounds(0, 0, side, side)
            draw(android.graphics.Canvas(bitmap))
        }
        var left = side; var top = side; var right = -1; var bottom = -1
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        pixels.forEachIndexed { index, pixel ->
            if (android.graphics.Color.alpha(pixel) > 16) {
                val x = index % side; val y = index / side
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) ScenarioInk(0f, 0f, side.toFloat(), side.toFloat())
        else ScenarioInk(left.toFloat(), top.toFloat(), (right - left + 1).toFloat(), (bottom - top + 1).toFloat())
    } finally { bitmap.recycle() }
}

@Composable
internal fun VpnScenarioConnectionText(label: String, color: Color,
    mode: VpnRoutingMode, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val context = LocalContext.current
    val resource = vpnScenarioStatusIcon(mode)
    val painter = painterResource(resource)
    val ink = remember(context, resource) { scenarioInk(context, resource) }
    val capital = label.firstOrNull { it.isUpperCase() } ?: label.firstOrNull { it.isLetter() } ?: 'H'
    val typeface = LocalFontFamilyResolver.current.resolve(style.fontFamily,
        style.fontWeight ?: FontWeight.Normal, style.fontStyle ?: FontStyle.Normal,
        style.fontSynthesis ?: FontSynthesis.All).value as? Typeface ?: Typeface.DEFAULT
    val capSize = remember(typeface, style.fontSize, capital, density.density, density.fontScale) {
        val bounds = Rect()
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = with(density) { style.fontSize.toPx() }
            getTextBounds(capital.toString(), 0, 1, bounds)
        }
        with(density) { bounds.height().coerceAtLeast(1).toFloat().toSp() }
    }
    Text(buildAnnotatedString {
        appendInlineContent("scenario", "\uFFFC")
        append('\u2009')
        append(label)
    }, modifier = modifier, style = style, color = color,
        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        inlineContent = mapOf("scenario" to InlineTextContent(
            Placeholder(capSize * (ink.width / ink.height), capSize, PlaceholderVerticalAlign.AboveBaseline)) {
            Canvas(Modifier.fillMaxSize()) {
                val factor = size.height / ink.height
                withTransform({ translate(-ink.left * factor, -ink.top * factor) }) {
                    with(painter) { draw(Size(96f * factor, 96f * factor), colorFilter = ColorFilter.tint(color)) }
                }
            }
        }))
}
