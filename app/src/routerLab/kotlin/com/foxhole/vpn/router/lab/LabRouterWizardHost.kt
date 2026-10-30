package com.foxhole.vpn.router.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.vpn.DefaultVpnController
import com.foxhole.vpn.R
import com.foxhole.vpn.router.RouterWizardCoordinator
import com.foxhole.vpn.router.RouterWizardPath
import com.foxhole.vpn.ui.LocalVpnNotices
import com.foxhole.vpn.ui.RouterWizardActions
import com.foxhole.vpn.ui.VpnBottomSheet
import com.foxhole.vpn.ui.VpnNoticeScope
import com.foxhole.vpn.ui.VpnNoticeWindow
import com.foxhole.vpn.ui.VpnRouterEntrySheet
import com.foxhole.vpn.ui.VpnRouterWizardSheet
import com.foxhole.vpn.ui.VpnUiTheme
import com.foxhole.vpn.ui.knownRouterHomeCountry
import com.foxhole.vpn.ui.routerWizardErrorLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun LabRouterWizardHost(wizard: RouterWizardCoordinator, controller: DefaultVpnController, requestAccess: ((() -> Unit) -> Unit)) {
    val settings by controller.settings.collectAsStateWithLifecycle()
    VpnUiTheme(settings.theme, settings.fontStyle) { VpnNoticeScope { WizardContent(wizard, controller, requestAccess) } }
}

@Composable
private fun WizardContent(wizard: RouterWizardCoordinator, controller: DefaultVpnController, requestAccess: ((() -> Unit) -> Unit)) {
    val state by wizard.state.collectAsStateWithLifecycle()
    val phone by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val notices = requireNotNull(LocalVpnNotices.current)
    val resources = LocalResources.current
    var visible by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf(true) }
    var generation by remember { mutableStateOf(0L) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val renderedGeneration = generation
    fun owned(ticket: Long): Boolean = visible && !entry && ticket == generation
    fun action(secret: CharArray? = null, ticket: Long = renderedGeneration, block: suspend () -> Unit) {
        if (!owned(ticket) || operation?.isActive == true) { secret?.fill('\u0000'); return }
        val next = scope.launch { try { block() } finally { secret?.fill('\u0000') } }
        next.invokeOnCompletion { secret?.fill('\u0000') }
        operation = next
    }
    fun close() { generation++; operation?.cancel(); wizard.close(); visible = false; entry = true }
    fun begin(path: RouterWizardPath) = requestAccess {
        if (visible && entry && renderedGeneration == generation) {
            wizard.begin(path, knownRouterHomeCountry(phone.deviceLocation.location?.countryCode))
            entry = false
            action { wizard.inspectAddress("192.168.1.1") }
        }
    }
    DisposableEffect(wizard) { onDispose { generation++; visible = false; operation?.cancel(); wizard.close() } }
    LaunchedEffect(state.error) { state.error?.let { notices.show(resources.getString(routerWizardErrorLabel(it)), error = true) } }
    Surface(Modifier.fillMaxSize()) {
        Column { Text("FoxHole Router Lab"); Button(onClick = { generation++; visible = true; entry = true }) { Text(stringResource(R.string.vpn_router)) } }
        VpnNoticeWindow()
        if (visible) VpnBottomSheet(onDismiss = ::close) {
            if (entry) VpnRouterEntrySheet({ begin(RouterWizardPath.SETUP) }, { begin(RouterWizardPath.PAIR) }, wizard.setupAvailable)
            else VpnRouterWizardSheet(state, phone.profiles, wizard.supportsHomeAutodetection, wizard.supportsRemoteProfile, wizard.installationCatalog,
                RouterWizardActions(
                    inspect = { address -> requestAccess { if (owned(renderedGeneration)) action { wizard.inspectAddress(address) } } },
                    trust = { action { wizard.acceptIdentity() } },
                    installProtocols = { if (owned(renderedGeneration)) wizard.installProtocols(it) },
                    confirmInstallProtocols = { action { wizard.confirmInstallProtocols() } },
                    login = { credential -> if (owned(renderedGeneration)) wizard.login(credential) else credential.fill('\u0000') },
                    newPin = { credential -> action(credential) { wizard.changePin(credential) } },
                    install = { action { wizard.install() } }, options = { if (owned(renderedGeneration)) wizard.options(it) },
                    finish = { action { wizard.finish() } },
                    failure = { if (owned(renderedGeneration)) notices.show(resources.getString(R.string.vpn_router_error_network), error = true) },
                ))
        }
    }
}
