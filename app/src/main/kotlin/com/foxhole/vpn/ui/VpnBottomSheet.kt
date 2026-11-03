package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VpnBottomSheet(onDismiss: () -> Unit, onBack: (() -> Unit)? = null, gesturesEnabled: Boolean = true,
    showCloseButton: Boolean = true, confineContentGestures: Boolean = false,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalVpnColors.current
    val back = rememberUpdatedState(onBack)
    val dismiss = rememberUpdatedState(onDismiss)
    val explicitClose = remember { mutableStateOf(false) }
    val noticeHandoff = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val confirmValueChange = remember {
        { value: SheetValue ->
            val action = back.value
            if (value == SheetValue.Hidden && action != null && !explicitClose.value) { action(); false } else true
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmValueChange)
    val sheetMotionSpec = remember { MaterialSheetMotion.fastSpatialSpec() }
    val hideMotionSpec = remember { androidx.compose.animation.core.tween<Float>(180, easing = androidx.compose.animation.core.FastOutSlowInEasing) }
    val bodyGestures = if (confineContentGestures) Modifier.nestedScroll(SheetBodyScrollConnection)
        .scrollable(rememberScrollableState { 0f }, Orientation.Vertical) else Modifier
    val onClose: () -> Unit = {
        if (!explicitClose.value) {
            explicitClose.value = true
            scope.launch {
                try {
                    sheetState.hide()
                    if (!sheetState.isVisible) dismiss.value()
                } finally { explicitClose.value = false }
            }
        }
    }
    VpnModalTheme {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetGesturesEnabled = gesturesEnabled,
            containerColor = colors.panel,
            contentColor = colors.foreground,
            dragHandle = null,
            contentWindowInsets = { WindowInsets.ime.only(WindowInsetsSides.Bottom) },
            content = {
                SideEffect { MaterialSheetMotion.applyFastSpatialSpec(sheetState, sheetMotionSpec, hideMotionSpec) }
                CompositionLocalProvider(LocalVpnSheetClose provides onClose, LocalVpnSheetBodyGestures provides bodyGestures) {
                val density = LocalDensity.current
                val windowHeight = LocalWindowInfo.current.containerSize.height
                val topInset = WindowInsets.systemBars.getTop(density)
                val bottomInset = WindowInsets.systemBars.getBottom(density)
                val navigationInset = WindowInsets.navigationBars.getBottom(density)
                val imeInset = WindowInsets.ime.getBottom(density)
                val navigationPadding = with(density) { if (imeInset > navigationInset) 0.dp else navigationInset.toDp() }
                val normalAvailable = (windowHeight - topInset - bottomInset).coerceAtLeast(0)
                val maximumHeight = with(density) {
                    (normalAvailable * .85f).toDp()
                }
                // Reserve the dialog navigation area explicitly; parent inset consumption must not erase it.
                // Native content insets retain IME avoidance without adding the navigation area twice.
                // Constant window constraints; AnimatedContent owns page-height motion.
                val requestedHeight = heightFraction?.let { fraction ->
                    require(fraction > 0f && fraction <= .85f)
                    with(density) { (normalAvailable * fraction).toDp() }
                }
                Column(Modifier.fillMaxWidth().then(when {
                    requestedHeight != null -> Modifier.height(requestedHeight)
                    windowHeight > 0 -> Modifier.heightIn(max = maximumHeight)
                    else -> Modifier
                }).padding(bottom = navigationPadding)) {
                    val handleDescription = stringResource(R.string.vpn_sheet_handle)
                    Box(Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = handleDescription },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).background(colors.muted, RoundedCornerShape(2.dp)))
                    }
                    SideEffect {
                        if (sheetState.targetValue == SheetValue.Expanded) noticeHandoff.value = false
                        else if (sheetState.isVisible && sheetState.targetValue == SheetValue.Hidden) noticeHandoff.value = true
                    }
                    VpnNoticeWindow(closing = explicitClose.value || noticeHandoff.value)
                    Column(Modifier.weight(1f, fill = heightFraction != null).fillMaxWidth().clipToBounds()) { content() }
                    footerContent?.invoke(this)
                    if (showCloseButton) VpnSheetCloseButton(onClose, onBack)
                }
                }
            },
        )
    }
}

internal val LocalVpnSheetClose = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalVpnSheetBodyGestures = staticCompositionLocalOf<Modifier> { Modifier }

private object SheetBodyScrollConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

@Composable
private fun VpnSheetCloseButton(onClick: () -> Unit, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.router_review_back))
        }
        OutlinedButton(onClick, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.vpn_close))
        }
    }
}

@Composable
internal fun VpnModalTheme(content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    val modal = remember(typography) {
        typography.copy(labelLarge = typography.bodyMedium, labelMedium = typography.bodySmall, labelSmall = typography.bodySmall)
    }
    MaterialTheme(typography = modal, content = content)
}
