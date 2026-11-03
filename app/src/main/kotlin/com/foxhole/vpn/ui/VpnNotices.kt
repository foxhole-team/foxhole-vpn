package com.foxhole.vpn.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
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

internal data class VpnNotice(
    val id: Long,
    val text: String,
    val error: Boolean,
    val persistent: Boolean,
    val runtime: Boolean,
    val lifetime: NoticeLifetime?,
    val success: Boolean = false,
)

internal class VpnNoticeState(private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    val motionScope: CoroutineScope? = null) {
    private var nextId = 0L
    private val windows = mutableStateListOf<Any>()
    private val leavingWindows = mutableStateListOf<Any>()
    private val motions = mutableMapOf<Long, NoticeDismissState>()
    private val announcedNotices = mutableSetOf<Long>()
    private val presentedNotices = mutableSetOf<Long>()
    var current by mutableStateOf<VpnNotice?>(null)
        private set
    val topWindow: Any? get() = windows.lastOrNull()

    fun show(text: String, error: Boolean = false, persistent: Boolean = false, runtime: Boolean = false, success: Boolean = false) {
        if (text.isNotBlank()) {
            val notice = VpnNotice(++nextId, text, error, persistent, runtime,
                if (persistent) null else NoticeLifetime(elapsedRealtime()), success = success && !error)
            current = notice
            pruneAnnouncements()
        }
    }

    fun dismiss(id: Long? = current?.id) {
        if (current?.id == id) {
            current = null
            pruneAnnouncements()
        }
    }

    fun clearRuntime() {
        if (current?.runtime == true) current = null
        pruneAnnouncements()
    }
    fun clearTransient() { if (current?.runtime == false) dismiss() }
    fun claimAnnouncement(window: Any, id: Long): Boolean =
        topWindow === window && current?.id == id && announcedNotices.add(id)
    fun claimEntrance(window: Any, id: Long): Boolean =
        topWindow === window && current?.id == id && presentedNotices.add(id)
    private fun pruneAnnouncements() {
        val retained = listOfNotNull(current?.id).toSet()
        announcedNotices.retainAll(retained)
        presentedNotices.retainAll(retained)
        motions.keys.filter { it !in retained }.forEach { motions.remove(it)?.stop() }
    }
    fun motion(id: Long): NoticeDismissState = motions.getOrPut(id) { NoticeDismissState() }
    fun attach(window: Any) { windows.add(window) }
    fun handoff(window: Any, leaving: Boolean) {
        if (leaving && window in windows && window !in leavingWindows) leavingWindows.add(window)
        else if (!leaving) leavingWindows.remove(window)
    }
    fun draws(window: Any): Boolean = topWindow === window || topWindow in leavingWindows
    fun detach(window: Any) { windows.remove(window); leavingWindows.remove(window) }
}

internal val LocalVpnNotices = staticCompositionLocalOf<VpnNoticeState?> { null }

@Composable
internal fun VpnNoticeScope(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val notices = remember(scope) { VpnNoticeState(motionScope = scope) }
    val notice = notices.current
    NoticeExpiryEffect(notice?.id, notice?.lifetime) { notices.dismiss(notice?.id) }
    CompositionLocalProvider(LocalVpnNotices provides notices, content = content)
}

@Composable
internal fun VpnNoticeWindow(closing: Boolean = false) {
    val notices = LocalVpnNotices.current ?: return
    val window = remember { Any() }
    DisposableEffect(notices, window) {
        notices.attach(window)
        onDispose { notices.detach(window) }
    }
    SideEffect { notices.handoff(window, closing) }
    val notice = notices.current ?: return
    val ownsInteraction = notices.topWindow === window
    val visible = notices.draws(window)
    val density = LocalDensity.current
    val host = LocalView.current.rootView
    LifecycleResumeEffect(notices, window, notice.id) {
        if (notices.claimAnnouncement(window, notice.id)) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onPauseOrDispose { }
    }
    val frame = Rect().also(host::getWindowVisibleDisplayFrame)
    val width = with(density) { (frame.width().takeIf { it > 0 } ?: host.width.coerceAtLeast(1)).toDp() }
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(host, margin) { VpnNoticePosition(host, margin) }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(
            flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (ownsInteraction) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            inheritSecurePolicy = true, dismissOnBackPress = false, dismissOnClickOutside = false,
            excludeFromSystemGesture = true, usePlatformDefaultWidth = false,
        ),
    ) {
        // Closing a sheet transfers the same notice to its parent window without replaying its entrance.
        val entrance = remember(notice.id) { Animatable(if (notices.claimEntrance(window, notice.id)) 0f else 1f) }
        LaunchedEffect(notice.id) { entrance.animateTo(1f, tween(160)) }
        val colors = LocalVpnColors.current
        val successColor = if (colors.light) Color(0xFF1E6034) else Color(0xFF8DDDAB)
        val noticeColor = when {
            notice.error -> MaterialTheme.colorScheme.onErrorContainer
            notice.success -> successColor
            else -> colors.muted
        }
        val progress = rememberNoticeProgress(notice.id, notice.lifetime)
        NoticeDismissible(
            noticeId = notice.id,
            motionState = notices.motion(notice.id),
            motionScope = notices.motionScope,
            onDismiss = {
                if (notices.current?.id == notice.id) {
                    host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    notices.dismiss(notice.id)
                }
            },
            // Keep the parent popup laid out behind the modal popup so closing the dialog exposes
            // an existing surface immediately. Only the top window receives input or accessibility.
            modifier = Modifier.width(width).padding(horizontal = 8.dp)
                .graphicsLayer { alpha = if (visible) 1f else 0f }
                .then(if (ownsInteraction) Modifier else Modifier.clearAndSetSemantics { }),
        ) { _ ->
            Surface(
                Modifier.fillMaxWidth()
                    .graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * -8.dp.toPx() }
                    .semantics { liveRegion = if (notice.error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
                shape = RoundedCornerShape(18.dp),
                color = when {
                    notice.error -> MaterialTheme.colorScheme.errorContainer
                    notice.success -> if (colors.light) Color(0xFFE1F2E5) else Color(0xFF143321)
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = noticeColor,
                shadowElevation = 4.dp,
            ) {
                Row(
                    Modifier.noticeRemainingLine(progress, noticeColor)
                        .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(if (notice.error) R.drawable.lin_warning else R.drawable.lin_info), null, Modifier.size(22.dp))
                    Text(notice.text, Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        color = if (notice.error || notice.success) noticeColor else colors.foreground,
                        style = MaterialTheme.typography.bodyMedium)
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
