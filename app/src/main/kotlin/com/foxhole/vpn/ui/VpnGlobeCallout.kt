package com.foxhole.vpn.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.VpnLocation

@Composable
internal fun VpnGlobeCallout(location: VpnLocation, modifier: Modifier = Modifier, below: Boolean = false) {
    val colors = LocalVpnColors.current
    val appearance = remember(location.countryCode, location.country, location.city) { Animatable(0f) }
    LaunchedEffect(appearance) {
        appearance.animateTo(1f, tween(200, easing = CubicBezierEasing(.05f, .7f, .1f, 1f)))
    }
    Column(
        modifier.graphicsLayer {
            alpha = appearance.value
            scaleX = .96f + appearance.value * .04f
            scaleY = scaleX
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (below) GlobeCalloutPointer(colors.panel, colors.border, aboveCard = true)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.panel,
            contentColor = colors.foreground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = .7f)),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VpnCountryFlag(location.countryCode)
                    Text(
                        location.country.ifBlank { location.countryCode },
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (location.city.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CityIcon(colors.muted)
                        Text(
                            location.city,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (!below) GlobeCalloutPointer(colors.panel, colors.border, aboveCard = false)
    }
}

@Composable
private fun GlobeCalloutPointer(panel: Color, border: Color, aboveCard: Boolean) {
    Canvas(Modifier.size(width = 16.dp, height = 8.dp).offset(y = if (aboveCard) 1.dp else (-1).dp)) {
        val baseY = if (aboveCard) size.height else 0f
        val tipY = if (aboveCard) 0f else size.height
        val left = Offset(0f, baseY)
        val tip = Offset(size.width * .5f, tipY)
        val right = Offset(size.width, baseY)
        val pointer = Path().apply {
            moveTo(left.x, left.y)
            lineTo(tip.x, tip.y)
            lineTo(right.x, right.y)
            close()
        }
        drawPath(pointer, panel)
        drawLine(border.copy(alpha = .7f), left, tip, 1.dp.toPx())
        drawLine(border.copy(alpha = .7f), tip, right, 1.dp.toPx())
    }
}

internal fun globeCalloutBelow(home: Offset?, server: Offset?, cardHeight: Float, viewportHeight: Float, gap: Float): Boolean =
    home != null && server != null && home.x.isFinite() && home.y.isFinite() && server.x.isFinite() && server.y.isFinite() &&
        cardHeight.isFinite() && cardHeight > 0f && viewportHeight.isFinite() && viewportHeight > 0f && gap.isFinite() && gap >= 0f &&
        home.y < server.y && server.y + gap + cardHeight <= viewportHeight - gap

internal fun globeCalloutVerticalOffset(anchorY: Float, cardHeight: Float, viewportHeight: Float, gap: Float, below: Boolean): Float {
    if (!anchorY.isFinite() || !cardHeight.isFinite() || cardHeight < 0f || !viewportHeight.isFinite() || viewportHeight < 0f ||
        !gap.isFinite() || gap < 0f
    ) return 0f
    val candidate = if (below) anchorY + gap else anchorY - cardHeight - gap
    return candidate.coerceIn(0f, (viewportHeight - cardHeight).coerceAtLeast(0f))
}

@Composable
private fun VpnCountryFlag(countryCode: String) {
    VpnFlagIcon(countryCode, MaterialTheme.typography.bodySmall)
}

@Composable
private fun CityIcon(color: Color) {
    Canvas(Modifier.size(width = 21.dp, height = 14.dp)) {
        val unit = size.height / 14f
        val stroke = Stroke(1.2f * unit, cap = StrokeCap.Round)
        drawRect(color, Offset(3f * unit, 5f * unit), Size(5f * unit, 8f * unit), style = stroke)
        drawRect(color, Offset(9f * unit, 1f * unit), Size(7f * unit, 12f * unit), style = stroke)
        for (row in 0..2) for (column in 0..1) {
            val point = Offset((11f + column * 3f) * unit, (4f + row * 3f) * unit)
            drawCircle(color, .5f * unit, point)
        }
        drawLine(color, Offset(unit, 13f * unit), Offset(19f * unit, 13f * unit), stroke.width, StrokeCap.Round)
    }
}
