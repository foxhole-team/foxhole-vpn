package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.vpn.router.ROUTER_PIN_DIGITS
import com.foxhole.vpn.R

@Composable
internal fun VpnRouterPinEntry(
    address: String,
    packageVersion: String?,
    credential: TextFieldState,
    active: Boolean,
    enabled: Boolean,
    onClose: (() -> Unit)?,
    retryAfterSeconds: Int = 0,
    errorLabel: Int? = null,
    refocusGeneration: Long = 0,
) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
        val split = maxWidth >= 560.dp && maxWidth > maxHeight
        val compact = maxHeight < 420.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                VpnRouterHelpButton(R.string.vpn_router_pin, R.string.router_help_pin)
            }
            val body = Modifier.weight(1f).fillMaxWidth().then(LocalVpnSheetBodyGestures.current).verticalScroll(rememberScrollState())
            if (split) {
                Row(body, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    VpnRouterPinBrand(address, packageVersion, compact = true, modifier = Modifier.weight(1f))
                    VpnRouterPinDetails(credential, active, enabled, retryAfterSeconds, errorLabel, refocusGeneration, Modifier.weight(1f))
                }
            } else {
                Column(body, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
                    VpnRouterPinBrand(address, packageVersion, compact)
                    VpnRouterPinDetails(credential, active, enabled, retryAfterSeconds, errorLabel, refocusGeneration)
                }
            }
            OutlinedButton(onClick = { onClose?.invoke() }, enabled = active && onClose != null,
                modifier = Modifier.fillMaxWidth().then(LocalVpnSheetBodyGestures.current).heightIn(min = 48.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.vpn_cancel))
            }
        }
    }
}

@Composable
private fun VpnRouterPinBrand(address: String, packageVersion: String?, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(painterResource(R.drawable.ic_launcher_openwrt), contentDescription = null,
            modifier = Modifier.size(if (compact) 48.dp else 68.dp), contentScale = ContentScale.Fit)
        Text(stringResource(R.string.router_login_brand), modifier = Modifier.fillMaxWidth(),
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center, maxLines = 2)
        Text(if (packageVersion == null) stringResource(R.string.router_login_version_unknown)
            else stringResource(R.string.router_login_version, packageVersion),
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(address, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VpnRouterPinDetails(credential: TextFieldState, active: Boolean,
    enabled: Boolean, retryAfterSeconds: Int, errorLabel: Int?, refocusGeneration: Long, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.router_login_enter_pin), style = MaterialTheme.typography.bodyMedium)
        }
        VpnRouterPinSlots(credential, active, enabled, refocusGeneration)
        if (retryAfterSeconds > 0) Text(stringResource(R.string.router_pin_cooldown, retryAfterSeconds),
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        else if (errorLabel != null) Text(stringResource(errorLabel), modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VpnRouterPinSlots(credential: TextFieldState, active: Boolean, enabled: Boolean,
    refocusGeneration: Long) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current
    val focusedWindow = window.isWindowFocused
    val currentEnabled by rememberUpdatedState(active && enabled)
    var focused by remember { mutableStateOf(false) }
    val prompt = stringResource(R.string.router_login_enter_pin)
    val digitsOnly = remember {
        InputTransformation.maxLength(ROUTER_PIN_DIGITS).then(InputTransformation {
            if (asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
        })
    }
    LaunchedEffect(active, enabled, focusedWindow, refocusGeneration) {
        if (active && enabled && focusedWindow) {
            withFrameNanos { }
            if (currentEnabled && window.isWindowFocused) {
                requester.requestFocus()
                keyboard?.show()
            }
        }
    }
    BasicSecureTextField(
        state = credential,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = prompt },
        enabled = active && enabled,
        inputTransformation = digitsOnly,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        onKeyboardAction = { keyboard?.hide() },
        textObfuscationMode = TextObfuscationMode.Hidden,
        decorator = { inner ->
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val spacing = 8.dp
                val slotWidth = minOf(46.dp, (maxWidth - spacing * (ROUTER_PIN_DIGITS - 1)) / ROUTER_PIN_DIGITS)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing), modifier = Modifier.clearAndSetSemantics { }) {
                    repeat(ROUTER_PIN_DIGITS) { index ->
                        Box(Modifier.size(slotWidth, 52.dp).border(1.dp,
                            if (focused && index == minOf(credential.text.length, ROUTER_PIN_DIGITS - 1)) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(if (index < credential.text.length) "●" else "○", fontSize = 25.sp,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { inner() }
            }
        },
    )
}
