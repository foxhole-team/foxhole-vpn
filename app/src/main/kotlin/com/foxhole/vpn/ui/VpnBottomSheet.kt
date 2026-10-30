package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
    fixedHeightFraction: Float? = null, showCloseButton: Boolean = true, confineContentGestures: Boolean = false,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalVpnColors.current
    val back = rememberUpdatedState(onBack)
    val dismiss = rememberUpdatedState(onDismiss)
    val explicitClose = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val confirmValueChange = remember {
        { value: SheetValue ->
            val action = back.value
            if (value == SheetValue.Hidden && action != null && !explicitClose.value) { action(); false } else true
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmValueChange)
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
            dragHandle = if (fixedHeightFraction == null) ({ BottomSheetDefaults.DragHandle() }) else null,
            content = {
                CompositionLocalProvider(LocalVpnSheetClose provides onClose, LocalVpnSheetBodyGestures provides bodyGestures) {
                if (fixedHeightFraction != null) {
                    require(fixedHeightFraction in .5f..1f)
                    val density = LocalDensity.current
                    val windowHeight = LocalWindowInfo.current.containerSize.height
                    val topInset = WindowInsets.systemBars.getTop(density)
                    val bottomInset = WindowInsets.systemBars.getBottom(density)
                    val imeInset = WindowInsets.ime.getBottom(density)
                    val normalAvailable = (windowHeight - topInset - bottomInset).coerceAtLeast(0)
                    val keyboardAvailable = (windowHeight - topInset - maxOf(bottomInset, imeInset)).coerceAtLeast(0)
                    val fixedHeight = with(density) {
                        minOf(normalAvailable * fixedHeightFraction, keyboardAvailable.toFloat()).toDp()
                    }
                    Column(Modifier.fillMaxWidth().height(fixedHeight)) {
                        BottomSheetDefaults.DragHandle(modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
                        VpnNoticeWindow()
                        Column(Modifier.weight(1f)) { content() }
                        footerContent?.invoke(this)
                        if (showCloseButton) VpnSheetCloseButton(onClose)
                    }
                } else {
                    VpnNoticeWindow()
                    Column(Modifier.weight(1f, fill = false)) { content() }
                    footerContent?.invoke(this)
                    if (showCloseButton) VpnSheetCloseButton(onClose)
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
private fun VpnSheetCloseButton(onClick: () -> Unit) {
    OutlinedButton(onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        .padding(top = 8.dp, bottom = 20.dp).heightIn(min = 56.dp)) {
        Text(stringResource(R.string.vpn_close))
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
