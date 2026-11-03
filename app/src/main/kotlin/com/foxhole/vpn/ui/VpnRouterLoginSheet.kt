package com.foxhole.vpn.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import com.foxhole.vpn.router.ROUTER_PIN_DIGITS
import com.foxhole.vpn.router.RouterPinEntryGate
import com.foxhole.vpn.router.RouterWizardError
import com.foxhole.vpn.router.routerPinCooldownRemaining
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import com.foxhole.vpn.router.readRouterSshPrivateKey
import com.foxhole.vpn.router.RouterIoOperation
import com.foxhole.vpn.router.routerIo
import com.foxhole.vpn.router.routerSshPrivateKeyBytes

internal typealias VpnRouterLoginLayout = @Composable (
    Boolean,
    @Composable RowScope.() -> Unit,
    @Composable ColumnScope.() -> Unit,
    @Composable ColumnScope.() -> Unit,
) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VpnRouterLoginSheet(
    installed: Boolean,
    gateway: String,
    busy: Boolean,
    onSubmit: suspend (address: String, credential: CharArray) -> Unit,
    onFailure: () -> Unit,
    onKeySubmit: (suspend (address: String, privateKey: ByteArray, passphrase: CharArray) -> Unit)? = null,
    addressEditable: Boolean = true,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    packageVersion: String? = null,
    active: Boolean = true,
    layout: VpnRouterLoginLayout? = null,
    pinCooldownUntilMs: Long? = null,
    pinError: RouterWizardError? = null,
) {
    val address = remember { TextFieldState(gateway) }
    val credential = remember(installed) { TextFieldState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    var privateKey by remember(installed) { mutableStateOf<ByteArray?>(null) }
    var readingKey by remember(installed) { mutableStateOf(false) }
    var keyReadFailed by remember(installed) { mutableStateOf(false) }
    var submitting by remember(installed) { mutableStateOf(false) }
    var pinAttempt by remember(installed) { mutableStateOf(0L) }
    var rejectedAttempt by remember(installed) { mutableStateOf(0L) }
    DisposableEffect(credential) {
        onDispose { credential.edit { replace(0, length, "") }; privateKey?.fill(0) }
    }
    fun readKey(read: (RouterIoOperation) -> ByteArray) {
        if (readingKey || busy || submitting) return
        readingKey = true
        val pending = AtomicReference<ByteArray?>()
        scope.launch {
            try {
                routerIo(Dispatchers.IO) { operation -> pending.set(read(operation)) }
                val loaded = requireNotNull(pending.getAndSet(null))
                privateKey?.fill(0)
                privateKey = loaded
                credential.edit { replace(0, length, "") }
                keyReadFailed = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { keyReadFailed = true }
            catch (_: SecurityException) { keyReadFailed = true }
            finally { pending.getAndSet(null)?.fill(0); readingKey = false }
        }
    }
    val keyFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readKey { operation ->
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("router_key_read_failed")
            operation.register(source)
            source.use(::readRouterSshPrivateKey)
        }
    }
    val cooldown by produceState(routerPinCooldownRemaining(pinCooldownUntilMs), pinCooldownUntilMs) {
        value = routerPinCooldownRemaining(pinCooldownUntilMs)
        while (value > 0) { delay(1000); value = routerPinCooldownRemaining(pinCooldownUntilMs) }
    }
    val ready = cooldown == 0 && !readingKey && address.text.isNotBlank() &&
        (!installed || credential.text.length == ROUTER_PIN_DIGITS && credential.text.all { it in '0'..'9' })
    fun submit() {
        if (!active || busy || submitting || readingKey || cooldown > 0 || address.text.isBlank() ||
            installed && (credential.text.length != ROUTER_PIN_DIGITS || credential.text.any { it !in '0'..'9' })) return
        submitting = true
        if (installed) pinAttempt += 1
        val host = address.text.toString().trim()
        val secret = CharArray(credential.text.length) { credential.text[it] }
        val key = privateKey
        privateKey = null
        credential.edit { replace(0, length, "") }
        val job = scope.launch {
            try {
                if (key == null) onSubmit(host, secret)
                else requireNotNull(onKeySubmit)(host, key, secret)
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { onFailure() }
            finally { secret.fill('\u0000'); key?.fill(0); submitting = false }
        }
        job.invokeOnCompletion { secret.fill('\u0000'); key?.fill(0) }
    }
    val autoSubmitAllowed by rememberUpdatedState(active && !busy && !submitting && !readingKey && cooldown == 0)
    val currentSubmit by rememberUpdatedState(::submit)
    LaunchedEffect(installed, credential) {
        if (installed) {
            val gate = RouterPinEntryGate()
            snapshotFlow { credential.text.length }.collect { length ->
                if (gate.observe(length, autoSubmitAllowed)) currentSubmit()
            }
        }
    }
    LaunchedEffect(installed, pinError, busy, submitting, pinAttempt) {
        if (installed && pinError == RouterWizardError.PIN && !busy && !submitting && pinAttempt > rejectedAttempt) {
            rejectedAttempt = pinAttempt
            credential.edit { replace(0, length, "") }
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        }
    }
    if (installed) {
        VpnRouterPinEntry(
            address = address.text.toString(), packageVersion = packageVersion, credential = credential,
            active = active, enabled = !busy && !submitting && cooldown == 0,
            retryAfterSeconds = cooldown, refocusGeneration = rejectedAttempt,
            errorLabel = pinError?.takeIf { !busy && !submitting }
                ?.takeUnless { it == RouterWizardError.LOCKED && pinCooldownUntilMs != null && cooldown == 0 }
                ?.let(::routerWizardErrorLabel),
            onClose = onClose ?: onBack,
        )
        return
    }
    val render: VpnRouterLoginLayout = layout ?: { working, headerActions, footer, content ->
        VpnRouterSheetContent(if (installed) R.string.vpn_router_pin else R.string.vpn_router_password,
            R.drawable.lin_router, titleHelp = if (installed) R.string.router_help_pin else R.string.router_help_password,
            busy = working, actions = headerActions, footer = footer, content = content)
    }
    render(
        busy || submitting || readingKey,
        {},
        {
            OutlinedButton(
                onClick = ::submit,
                enabled = !busy && !submitting && ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (layout == null) 52.dp else 48.dp),
                border = BorderStroke(1.5.dp, if (installed || layout != null) routerConnectColor() else MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor =
                    if (installed || layout != null) routerConnectColor() else MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(if (installed) R.string.vpn_router_pair else R.string.vpn_router_check)) }
            if (layout == null) VpnRouterNavigationFooter(!busy && !submitting, onBack, onClose)
            else VpnRouterWizardNavigationFooter(!busy && !submitting, onBack, onClose)
        },
        {
            if (!installed) Text(stringResource(R.string.vpn_router_root_login))
            OutlinedTextField(
                state = address,
                modifier = Modifier.fillMaxWidth(),
                enabled = addressEditable && !busy && !submitting && !readingKey,
                lineLimits = TextFieldLineLimits.SingleLine,
                inputTransformation = InputTransformation.maxLength(253),
                label = { Text(stringResource(R.string.vpn_router_address)) },
            )
            if (!installed && onKeySubmit != null) {
                OutlinedButton(onClick = { keyFile.launch("*/*") }, enabled = !busy && !submitting && !readingKey,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.router_ssh_key_file))
                }
                OutlinedButton(onClick = {
                    try {
                        if (!view.hasWindowFocus()) throw IOException("router_clipboard_focus")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                            ?: throw IOException("router_clipboard_empty")
                        readKey { _ -> routerSshPrivateKeyBytes(text) }
                    } catch (_: IOException) { keyReadFailed = true }
                    catch (_: SecurityException) { keyReadFailed = true }
                }, enabled = !busy && !submitting && !readingKey,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.router_ssh_key_paste))
                }
                if (privateKey != null) {
                    Text(stringResource(R.string.router_ssh_key_loaded), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        privateKey?.fill(0); privateKey = null
                        credential.edit { replace(0, length, "") }
                    }, enabled = !busy && !submitting && !readingKey) { Text(stringResource(R.string.router_ssh_use_password)) }
                }
                if (keyReadFailed) Text(stringResource(R.string.router_ssh_key_error), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedSecureTextField(
                state = credential,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !submitting && !readingKey,
                inputTransformation = InputTransformation.maxLength(if (installed) ROUTER_PIN_DIGITS else 1024),
                textObfuscationMode = TextObfuscationMode.Hidden,
                keyboardOptions = KeyboardOptions(keyboardType = if (installed) KeyboardType.NumberPassword else KeyboardType.Password),
                label = { Text(stringResource(when {
                    installed -> R.string.vpn_router_pin
                    privateKey != null -> R.string.router_ssh_key_passphrase
                    else -> R.string.vpn_router_password
                })) },
            )
        },
    )
}
