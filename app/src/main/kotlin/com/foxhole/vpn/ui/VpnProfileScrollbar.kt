package com.foxhole.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val VpnProfileScrollbarTag = "vpn-profile-scrollbar"

internal data class VpnProfileScrollThumb(val top: Float, val height: Float)

internal fun vpnProfileScrollThumb(offset: Int, viewport: Int, content: Int, track: Float, minimum: Float): VpnProfileScrollThumb? {
    if (offset < 0 || offset == Int.MAX_VALUE || viewport <= 0 || viewport == Int.MAX_VALUE || content <= viewport ||
        content == Int.MAX_VALUE || !track.isFinite() || track <= 0 || !minimum.isFinite() || minimum <= 0) return null
    val height = (track * viewport.toFloat() / content).coerceIn(minimum.coerceAtMost(track), track)
    val top = (track - height) * offset.coerceAtMost(content - viewport).toFloat() / (content - viewport)
    return VpnProfileScrollThumb(top, height)
}

@Composable
internal fun VpnProfileScrollbar(state: LazyListState, modifier: Modifier = Modifier, tag: String = VpnProfileScrollbarTag) {
    val visible by remember(state) { derivedStateOf { state.canScrollForward || state.canScrollBackward } }
    if (!visible) return
    val colors = LocalVpnColors.current
    Canvas(modifier.testTag(tag)) {
        val indicator = state.scrollIndicatorState ?: return@Canvas
        val inset = 2.dp.toPx()
        val track = size.height - inset * 2
        val thumb = vpnProfileScrollThumb(indicator.scrollOffset, indicator.viewportSize, indicator.contentSize, track, 24.dp.toPx())
            ?: return@Canvas
        val width = 4.dp.toPx()
        val corner = CornerRadius(width * .5f)
        drawRoundRect(colors.border.copy(alpha = .5f), Offset(size.width - width, inset), Size(width, track), corner)
        drawRoundRect(colors.muted.copy(alpha = .9f), Offset(size.width - width, inset + thumb.top), Size(width, thumb.height), corner)
    }
}
