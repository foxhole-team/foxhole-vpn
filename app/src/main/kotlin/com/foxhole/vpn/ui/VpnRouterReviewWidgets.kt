package com.foxhole.vpn.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun VpnRouterReviewTable(rows: List<Pair<String, String>>, icons: List<Int> = emptyList(), countryCodes: Map<Int, String> = emptyMap()) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = LocalVpnColors.current.panel,
        border = BorderStroke(1.dp, LocalVpnColors.current.border),
    ) {
        Column {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = LocalVpnColors.current.border)
                VpnRouterTableRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}) {
                    Icon(painterResource(icons.getOrNull(index) ?: R.drawable.lin_info), null,
                        Modifier.size(20.dp), tint = LocalVpnColors.current.muted)
                    Text(label, Modifier.weight(.42f), style = MaterialTheme.typography.bodySmall,
                        color = LocalVpnColors.current.muted)
                    val flag = countryCodes[index]
                    if (flag != null) Row(Modifier.weight(.58f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        VpnFlagIcon(countryCode = flag, style = MaterialTheme.typography.bodyMedium)
                        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                    } else Text(value, Modifier.weight(.58f), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
internal fun routerConnectColor(): Color = vpnColorsFor(
    if (LocalVpnColors.current.light) VpnTheme.LIGHT else VpnTheme.DARK,
    systemDark = false,
).connected

@Composable
internal fun VpnRouterNavigationFooter(backEnabled: Boolean, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    val close = onClose ?: LocalVpnSheetClose.current
    OutlinedButton(colors = vpnNeutralOutlinedButtonColors(), onClick = close,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.vpn_close))
    }
}

@Composable
internal fun VpnRouterIllustration(modifier: Modifier = Modifier, searching: Boolean = false, connected: Boolean = false) {
    val searchActive by rememberUpdatedState(searching)
    val signal = remember { Animatable(if (searching) 0f else 1f) }
    // Keep the current rise alive when discovery completes.
    LaunchedEffect(Unit) {
        while (currentCoroutineContext().isActive) {
            snapshotFlow { searchActive }.first { it }
            do {
                signal.snapTo(0f)
                signal.animateTo(1f, androidx.compose.animation.core.tween(2600,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing))
                if (searchActive) delay(180)
            } while (searchActive && currentCoroutineContext().isActive)
        }
    }
    val ink = LocalVpnColors.current.muted
    val signalInk = ink
    val arcs = remember { listOf(
                    androidx.compose.ui.graphics.Path().apply { moveTo(126f,69f); quadraticTo(140f,55f,154f,69f) },
                    androidx.compose.ui.graphics.Path().apply { moveTo(115f,57f); quadraticTo(140f,34f,165f,57f) },
                    androidx.compose.ui.graphics.Path().apply { moveTo(104f,45f); quadraticTo(140f,13f,176f,45f) },
    ) }

    androidx.compose.foundation.layout.Box(modifier.graphicsLayer { scaleX = 1.16f; scaleY = 1.16f }) {
        Icon(painterResource(R.drawable.router_review_body), null, Modifier.fillMaxSize().offset(y = 1.dp).blur(2.dp), tint = ink.copy(alpha = .12f))
        Icon(painterResource(R.drawable.router_review_body), null, Modifier.fillMaxSize(), tint = ink)
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val factor = minOf(size.width / 280f, size.height / 168f)
            withTransform({
                translate((size.width - 280f * factor) / 2f, (size.height - 168f * factor) / 2f)
                scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero)
            }) {
                drawCircle(signalInk.copy(alpha = .08f), radius = 6f, center = androidx.compose.ui.geometry.Offset(140f, 78f))
                drawCircle(signalInk, radius = 3f, center = androidx.compose.ui.geometry.Offset(140f, 78f))
                arcs.forEachIndexed { index, path ->
                    val alpha = androidx.compose.animation.core.FastOutSlowInEasing.transform(routerSignalOpacity(signal.value, index))
                    drawPath(path, signalInk.copy(alpha = alpha * .05f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(8f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawPath(path, signalInk.copy(alpha = alpha * .10f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawPath(path, signalInk.copy(alpha = alpha * .92f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val sweep = 90f + 170f * routerSignalOpacity(signal.value, index)
                    val shimmer = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(signalInk.copy(alpha = 0f), signalInk.copy(alpha = alpha * .6f), signalInk.copy(alpha = 0f)),
                        start = androidx.compose.ui.geometry.Offset(sweep - 36f, 0f),
                        end = androidx.compose.ui.geometry.Offset(sweep + 36f, 0f))
                    drawPath(path, shimmer,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
    }
}

internal fun routerSignalOpacity(progress: Float, index: Int): Float =
    ((progress - .10f - index * .20f) / .22f).coerceIn(0f, 1f)

@Composable
internal fun RouterPrimaryActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, active: Boolean = false, icon: Int? = null) {
    androidx.compose.material3.Button(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 60.dp), colors = vpnPrimaryButtonColors(active)) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(22.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
