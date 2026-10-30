package com.foxhole.vpn.ui

import android.graphics.Rect
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.foxhole.vpn.R
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

internal data class VpnNotice(val id: Long, val text: String, val error: Boolean, val persistent: Boolean, val runtime: Boolean)

internal class VpnNoticeState {
    private var nextId = 0L
    private val windows = mutableStateListOf<Any>()
    private var runtimeNotice: VpnNotice? = null
    private val announcedNotices = mutableSetOf<Long>()
    var current by mutableStateOf<VpnNotice?>(null)
        private set
    val topWindow: Any? get() = windows.lastOrNull()

    fun show(text: String, error: Boolean = false, persistent: Boolean = false, runtime: Boolean = false) {
        if (text.isNotBlank()) {
            val notice = VpnNotice(++nextId, text, error, persistent, runtime)
            if (runtime) runtimeNotice = notice
            current = notice
            pruneAnnouncements()
        }
    }

    fun dismiss(id: Long? = current?.id) {
        if (current?.id == id) {
            if (current?.runtime == true) runtimeNotice = null
            current = runtimeNotice
            pruneAnnouncements()
        }
    }

    fun clearRuntime() {
        runtimeNotice = null
        if (current?.runtime == true) current = null
        pruneAnnouncements()
    }
    fun clearTransient() { if (current?.runtime == false) dismiss() }
    fun claimAnnouncement(window: Any, id: Long): Boolean =
        topWindow === window && current?.id == id && announcedNotices.add(id)
    private fun pruneAnnouncements() { announcedNotices.retainAll(listOfNotNull(current?.id, runtimeNotice?.id).toSet()) }
    fun attach(window: Any) { windows.add(window) }
    fun detach(window: Any) { windows.remove(window) }
}

internal val LocalVpnNotices = staticCompositionLocalOf<VpnNoticeState?> { null }

@Composable
internal fun VpnNoticeScope(content: @Composable () -> Unit) {
    val notices = remember { VpnNoticeState() }
    val notice = notices.current
    LaunchedEffect(notice?.id) {
        if (notice != null && !notice.persistent) {
            delay(6_000)
            notices.dismiss(notice.id)
        }
    }
    CompositionLocalProvider(LocalVpnNotices provides notices, content = content)
}

@Composable
internal fun VpnNoticeWindow() {
    val notices = LocalVpnNotices.current ?: return
    val window = remember { Any() }
    DisposableEffect(notices, window) {
        notices.attach(window)
        onDispose { notices.detach(window) }
    }
    val notice = notices.current ?: return
    if (notices.topWindow !== window) return
    val density = LocalDensity.current
    val host = LocalView.current.rootView
    LifecycleResumeEffect(notices, window, notice.id) {
        if (notices.claimAnnouncement(window, notice.id)) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onPauseOrDispose { }
    }
    val frame = Rect().also(host::getWindowVisibleDisplayFrame)
    val width = with(density) { frame.width().coerceAtLeast(1).toDp() }.coerceAtMost(560.dp)
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(host, margin) { VpnNoticePosition(host, margin) }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        val entrance = remember(notice.id) { Animatable(0f) }
        LaunchedEffect(notice.id) { entrance.animateTo(1f, tween(160)) }
        val colors = LocalVpnColors.current
        Surface(
            Modifier.width(width).padding(horizontal = 16.dp)
                .graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * -8.dp.toPx() }
                .semantics { liveRegion = if (notice.error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
            shape = RoundedCornerShape(18.dp),
            color = if (notice.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (notice.error) MaterialTheme.colorScheme.onErrorContainer else colors.foreground,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (notice.error) R.drawable.lin_warning else R.drawable.lin_info), null, Modifier.size(22.dp))
                Text(notice.text, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    if (notices.topWindow === window && notices.current?.id == notice.id) {
                        host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        notices.dismiss(notice.id)
                    }
                }) {
                    Icon(painterResource(R.drawable.lin_plus), stringResource(R.string.vpn_close), Modifier.size(20.dp).rotate(45f))
                }
            }
        }
    }
}

private class VpnNoticePosition(private val host: View, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val screen = IntArray(2).also(host::getLocationOnScreen)
        val window = IntArray(2).also(host::getLocationInWindow)
        val frame = Rect().also(host::getWindowVisibleDisplayFrame)
        val insets = ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
        val safeTop = maxOf(frame.top, insets?.top ?: 0)
        val x = frame.left + ((frame.width() - popupContentSize.width) / 2).coerceAtLeast(0)
        val y = (safeTop + margin).coerceAtMost(maxOf(safeTop, frame.bottom - popupContentSize.height))
        return IntOffset(x - (screen[0] - window[0]), y - (screen[1] - window[1]))
    }
}

@Composable
internal fun VpnModalIcon(drawable: Int) {
    VpnNoticeWindow()
    Icon(painterResource(drawable), contentDescription = null, modifier = Modifier.size(24.dp))
}
