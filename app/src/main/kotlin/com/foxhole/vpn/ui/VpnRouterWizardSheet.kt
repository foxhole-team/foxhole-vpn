package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import com.foxhole.vpn.router.routerPinCooldownRemaining
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.router.RouterInstallCatalog
import com.foxhole.vpn.router.RouterInstallProgress
import com.foxhole.vpn.router.RouterInstallProgressStage
import com.foxhole.vpn.router.RouterInstallProtocol
import com.foxhole.vpn.router.RouterStorageMode
import com.foxhole.vpn.router.RouterUpdateFailureReason
import com.foxhole.vpn.router.RouterWizardError
import com.foxhole.vpn.router.RouterWizardOptions
import com.foxhole.vpn.router.RouterWizardPath
import com.foxhole.vpn.router.RouterWizardStage
import com.foxhole.vpn.router.RouterWizardState
import com.foxhole.vpn.router.ROUTER_PIN_DIGITS
import java.util.Locale

internal data class RouterWizardActions(
    val inspect: (String) -> Unit,
    val trust: () -> Unit,
    val installProtocols: (Set<RouterInstallProtocol>) -> Unit,
    val confirmInstallProtocols: () -> Unit,
    val login: suspend (CharArray) -> Unit,
    val newPin: (CharArray) -> Unit,
    val install: () -> Unit,
    val options: (RouterWizardOptions) -> Unit,
    val finish: (CharArray?) -> Unit,
    val failure: () -> Unit,
    val openRouterSetup: (() -> Unit)? = null,
    val installCompactSelection: ((Set<RouterInstallProtocol>, Boolean) -> Unit)? = null,
    val loginKey: (suspend (ByteArray, CharArray) -> Unit)? = null,
    val setupAddress: ((String) -> Unit)? = null,
)

@Composable
internal fun VpnRouterWizardSheet(
    state: RouterWizardState,
    profiles: List<VpnProfileSummary>,
    supportsHomeAutodetection: Boolean,
    supportsRemoteProfile: Boolean,
    installationCatalog: RouterInstallCatalog?,
    actions: RouterWizardActions,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val initialPin = remember(state.options.licenseGeneration, state.address) { TextFieldState() }
    val repeatedPin = remember(state.options.licenseGeneration, state.address) { TextFieldState() }
    DisposableEffect(initialPin, repeatedPin) {
        onDispose { initialPin.edit { replace(0, length, "") }; repeatedPin.edit { replace(0, length, "") } }
    }
    val pinCooldownSeconds by produceState(routerPinCooldownRemaining(state.pinCooldownUntilMs), state.pinCooldownUntilMs) {
        value = routerPinCooldownRemaining(state.pinCooldownUntilMs)
        while (value > 0) { delay(1000); value = routerPinCooldownRemaining(state.pinCooldownUntilMs) }
    }
    val initialPinReady = initialPin.text.length == ROUTER_PIN_DIGITS && initialPin.text.all { it in '0'..'9' } &&
        repeatedPin.text.length == ROUTER_PIN_DIGITS && initialPin.text.indices.all { initialPin.text[it] == repeatedPin.text[it] }
    var address by rememberSaveable(state.options.licenseGeneration, state.address) { mutableStateOf(state.address) }
    var protocols by remember(installationCatalog?.sha256, state.installSelection) {
        mutableStateOf(state.installSelection?.protocols ?: installationCatalog?.defaultSelection?.protocols.orEmpty())
    }
    var includeServer by remember(installationCatalog?.sha256, state.installSelection) {
        mutableStateOf(state.installSelection?.includeServer ?: false)
    }
    val discoveredPackageVersion = state.discovery?.takeIf { it.address == state.address &&
                    it.kind == com.foxhole.vpn.router.RouterDiscoveryKind.INSTALLED }?.packageVersion
    AnimatedContent(targetState = state.stage, modifier = Modifier.fillMaxWidth(), transitionSpec = {
        VpnSheetMotion.pageTransition(animateSize = true)
    }, label = "router-wizard") { stage ->
        val enabled = stage == state.stage && !state.busy
        when (stage) {
            RouterWizardStage.LOGIN -> VpnRouterLoginSheet(
                installed = state.path == RouterWizardPath.PAIR,
                packageVersion = discoveredPackageVersion,
                active = stage == state.stage,
                pinCooldownUntilMs = state.pinCooldownUntilMs,
                pinError = state.error,
                gateway = state.address,
                busy = !enabled,
                onSubmit = { _, credential -> actions.login(credential) },
                onFailure = actions.failure,
                onKeySubmit = actions.loginKey?.let { login -> { _, key, passphrase -> login(key, passphrase) } },
                addressEditable = false,
                onClose = onClose,
                onBack = onBack,
                layout = { working, headerActions, footer, content ->
                    RouterWizardContent(
                        if (state.path == RouterWizardPath.PAIR) R.string.vpn_router_pin else R.string.vpn_router_password,
                        R.drawable.lin_router, state.address, discoveredPackageVersion, working,
                        titleHelp = if (state.path == RouterWizardPath.PAIR) R.string.router_help_pin else R.string.router_help_password,
                        actions = headerActions, footer = footer, content = content,
                    )
                },
            )
            else -> RouterWizardContent(
                R.string.vpn_router,
                R.drawable.lin_router,
                address = if (stage == RouterWizardStage.ADDRESS) address else state.address,
                packageVersion = discoveredPackageVersion,
                busy = state.busy,
                footer = {
                    when (stage) {
                        RouterWizardStage.ADDRESS -> RouterFullButton(R.string.vpn_router_check, enabled && address.isNotBlank()) {
                            actions.inspect(address)
                        }
                        RouterWizardStage.IDENTITY -> Unit
                        RouterWizardStage.PIN_SETUP_REQUIRED -> actions.openRouterSetup?.let { open ->
                            RouterFullButton(R.string.router_review_open_router_setup, enabled, open)
                        }
                        RouterWizardStage.PROTOCOLS -> RouterFullButton(
                            R.string.vpn_continue,
                            enabled && installationCatalog?.accepts(protocols, includeServer) == true &&
                                (installationCatalog?.schema != 2 || actions.installCompactSelection != null),
                        ) {
                            if (installationCatalog?.schema == 2) actions.installCompactSelection?.invoke(protocols, includeServer)
                            else actions.installProtocols(protocols)
                            actions.confirmInstallProtocols()
                        }
                        RouterWizardStage.INSTALL -> RouterFullButton(R.string.vpn_router_install,
                            enabled && (state.path != RouterWizardPath.SETUP || state.options.licenseAccepted), actions.install)
                        RouterWizardStage.OPTIONS, RouterWizardStage.NEW_PIN -> RouterFullButton(
                            if (state.autoUpdateUnconfirmed) R.string.router_updates_status else R.string.vpn_router_done,
                            enabled && state.options.valid && (state.options.countryAuto || knownRouterHomeCountry(state.options.countryCode) != null) &&
                                (!state.initialPinRequired || initialPinReady && pinCooldownSeconds == 0 &&
                                    (!(state.licenseRequired || state.installedInThisWizard) || state.options.licenseAccepted)),
                        ) {
                            val secret = if (state.initialPinRequired) CharArray(initialPin.text.length) { initialPin.text[it] } else null
                            initialPin.edit { replace(0, length, "") }; repeatedPin.edit { replace(0, length, "") }
                            actions.finish(secret)
                        }
                        else -> Unit
                    }
                    VpnRouterWizardNavigationFooter(enabled, onBack, onClose)
                },
            ) {
                when (stage) {
                    RouterWizardStage.ADDRESS -> {
                        Text(stringResource(R.string.vpn_router_standard_network), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            address,
                            onValueChange = { if (it.length <= 15) address = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.vpn_router_address)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (state.path == RouterWizardPath.PAIR) actions.setupAddress?.let { setup ->
                            TextButton(onClick = { setup(address) }, enabled = enabled && address.isNotBlank(),
                                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text(stringResource(R.string.router_ssh_manual_setup))
                            }
                        }
                    }
                    RouterWizardStage.IDENTITY -> Text(stringResource(R.string.vpn_router_checking))
                    RouterWizardStage.PIN_SETUP_REQUIRED -> Text(stringResource(R.string.router_review_owner_setup_required),
                        style = MaterialTheme.typography.bodyMedium)
                    RouterWizardStage.CHECKING -> if (state.installProgress == null) Text(stringResource(R.string.router_ssh_checking_openwrt))
                    RouterWizardStage.PROTOCOLS -> {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            VpnRouterTableRow(Modifier.fillMaxWidth()) {
                                Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp))
                                VpnHelpLabel(stringResource(R.string.vpn_router_install_protocols), R.string.vpn_router_install_protocols,
                                    R.string.router_help_protocols, Modifier.weight(1f))
                            }
                            VpnRouterProtocolDropdown(installationCatalog, protocols, enabled) { protocols = it }
                        }
                        if (installationCatalog?.schema == 2) {
                            RouterCheckRow(R.string.vpn_router_install_server, R.string.router_help_install_server, includeServer, enabled) {
                                includeServer = it
                            }
                        }
                        if (state.path == RouterWizardPath.SETUP) {
                            RouterAutoUpdateChoice(state.options, enabled) {
                                actions.options(state.options.copy(autoUpdateEnabled = it))
                            }
                        }
                        if (protocols.isNotEmpty() && installationCatalog?.accepts(protocols, includeServer) != true) {
                            Text(stringResource(R.string.vpn_router_protocol_combination_unavailable), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RouterWizardStage.INSPECTED -> {
                        VpnRouterReviewTable(listOf(
                            stringResource(R.string.router_review_address) to state.address,
                            "OpenWrt" to state.openWrtVersion,
                            stringResource(R.string.router_review_architecture) to state.architecture,
                        ), icons = listOf(R.drawable.lin_router, R.drawable.lin_settings, R.drawable.lin_settings))
                        Text(stringResource(R.string.router_setup_readonly_complete), style = MaterialTheme.typography.bodyMedium)
                    }
                    RouterWizardStage.INSTALL -> {
                        if (!state.busy || state.installProgress == null) {
                            Text(stringResource(R.string.router_review_install_summary), style = MaterialTheme.typography.titleMedium)
                            VpnRouterReviewTable(listOf(
                                stringResource(R.string.router_review_address) to state.address,
                                "OpenWrt" to state.openWrtVersion,
                                stringResource(R.string.router_review_architecture) to state.architecture,
                                stringResource(R.string.router_review_protocols) to RouterInstallProtocol.entries
                                    .filter { it in state.installSelection?.protocols.orEmpty() }.joinToString(" · ") { it.label },
                                *(if (installationCatalog?.schema == 2) arrayOf(stringResource(R.string.vpn_router_install_server) to
                                    stringResource(if (state.installSelection?.includeServer == true) R.string.vpn_router_install_server_yes else R.string.vpn_router_install_server_no)) else emptyArray()),
                                stringResource(R.string.router_review_storage) to stringResource(
                                    if (state.storageMode == RouterStorageMode.RAM) R.string.vpn_router_ram else R.string.vpn_router_flash),
                                *(if (state.path == RouterWizardPath.SETUP) arrayOf(stringResource(R.string.vpn_router_setup_auto_updates) to
                                    stringResource(if (state.options.autoUpdateEnabled) R.string.vpn_router_setup_auto_on else R.string.vpn_router_setup_auto_off)) else emptyArray()),
                            ), icons = listOf(
                                R.drawable.lin_router, R.drawable.lin_settings, R.drawable.lin_settings, R.drawable.lin_power,
                                *(if (installationCatalog?.schema == 2) arrayOf(R.drawable.lin_profiles) else emptyArray()),
                                R.drawable.lin_download,
                                *(if (state.path == RouterWizardPath.SETUP) arrayOf(R.drawable.lin_download) else emptyArray()),
                            ))
                            if (state.path == RouterWizardPath.SETUP) RouterSetupLicenseChoice(state.options.licenseAccepted, enabled, state.options.licenseGeneration) {
                                actions.options(state.options.copy(licenseAccepted = it))
                            }
                            if (state.storageMode == RouterStorageMode.RAM) Text(stringResource(R.string.vpn_router_ram_note),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RouterWizardStage.OPTIONS, RouterWizardStage.NEW_PIN -> {
                        if (state.installedInThisWizard && state.options.localRules != null)
                            RouterLocalRoutingChoice(state.options.localRules, enabled) { actions.options(state.options.copy(localRules = it)) }
                        RouterOptionsContent(
                            state.options, profiles, enabled && !state.autoUpdateUnconfirmed, supportsHomeAutodetection, supportsRemoteProfile,
                            state.installedInThisWizard, state.autoUpdateUnconfirmed, state.autoUpdateFailure, actions.options,
                        )
                        if (state.initialPinRequired && state.installedInThisWizard) {
                            RouterInitialPinFields(initialPin, repeatedPin, enabled && pinCooldownSeconds == 0)
                            if (pinCooldownSeconds > 0) Text(stringResource(R.string.router_pin_cooldown, pinCooldownSeconds),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (state.licenseRequired && !state.options.licenseAccepted)
                                RouterSetupLicenseChoice(state.options.licenseAccepted, enabled, state.options.licenseGeneration) {
                                    actions.options(state.options.copy(licenseAccepted = it))
                                }
                        }
                    }
                    RouterWizardStage.FINISHING -> Text(stringResource(R.string.vpn_router_finishing))
                    RouterWizardStage.COMPLETE -> {
                        Text(stringResource(R.string.vpn_router_connected), style = MaterialTheme.typography.titleMedium)
                        if (state.remoteProfileCreated) Text(stringResource(
                            if (state.remoteReady) R.string.vpn_router_remote_ready else R.string.vpn_router_remote_check,
                        ))
                    }
                    else -> Unit
                }
                state.installProgress?.let { RouterInstallationProgress(it) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterInitialPinFields(pin: TextFieldState, repeat: TextFieldState, enabled: Boolean) {
    Text(stringResource(R.string.vpn_router_change_pin_note), style = MaterialTheme.typography.bodySmall)
    for ((field, label) in listOf(pin to R.string.router_setup_pin, repeat to R.string.router_setup_pin_repeat)) {
        OutlinedSecureTextField(
            state = field, modifier = Modifier.fillMaxWidth(), enabled = enabled,
            inputTransformation = InputTransformation.maxLength(ROUTER_PIN_DIGITS),
            textObfuscationMode = TextObfuscationMode.Hidden,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            label = { Text(stringResource(label)) },
        )
    }
}

@Composable
private fun RouterOptionsContent(
    options: RouterWizardOptions,
    profiles: List<VpnProfileSummary>,
    enabled: Boolean,
    supportsHome: Boolean,
    supportsRemote: Boolean,
    setupCompleted: Boolean,
    autoUpdateUnconfirmed: Boolean,
    autoUpdateFailure: RouterUpdateFailureReason?,
    onChange: (RouterWizardOptions) -> Unit,
) {
    if (setupCompleted) {
        RouterAutoUpdateChoice(options, enabled && !autoUpdateUnconfirmed) { onChange(options.copy(autoUpdateEnabled = it)) }
        autoUpdateFailure?.let { Text(stringResource(wizardAutoUpdateFailureLabel(it)), color = MaterialTheme.colorScheme.error) }
        if (autoUpdateUnconfirmed) Text(stringResource(R.string.vpn_router_setup_auto_unknown), style = MaterialTheme.typography.bodySmall)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        VpnRouterTableRow(Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
            VpnHelpLabel(stringResource(R.string.vpn_router_profile_title), R.string.vpn_router_profile_title,
                R.string.router_help_profile, Modifier.weight(1f))
        }
        VpnRouterProfileDropdown(profiles, options.profileId, enabled) { onChange(options.copy(profileId = it)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        VpnRouterTableRow(Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(20.dp))
            Text(stringResource(R.string.router_review_local_traffic), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            VpnRouterCountryFlag(options.countryCode, enabled,
                onSelect = { onChange(options.copy(countryCode = it, countryAuto = false)) },
                autoSelected = options.countryAuto,
                onAuto = { onChange(options.copy(countryAuto = true)) })
            VpnRouterHelpButton(R.string.router_review_local_traffic, R.string.router_help_country_source)
            VpnSwitch(options.countrySitesDirect, onCheckedChange = { onChange(options.copy(countrySitesDirect = it)) }, enabled = enabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (supportsHome) RouterCheckRow(R.string.vpn_router_auto_home, R.string.router_help_home, options.homeAutodetection, enabled) {
            onChange(options.copy(homeAutodetection = it))
        }
        if (supportsRemote) {
            RouterCheckRow(R.string.router_review_direct_ip, R.string.router_help_public_ip, options.publicIp, enabled) {
                onChange(options.copy(publicIp = it, createRemoteProfile = it && options.createRemoteProfile))
            }
            if (options.publicIp) {
                RouterHelpLabel(R.string.router_review_remote_settings, R.string.router_review_remote_help)
                RouterCheckRow(R.string.router_review_phone_profile, R.string.router_review_remote_help,
                    options.createRemoteProfile, enabled, showHelp = false) { onChange(options.copy(createRemoteProfile = it)) }
            }
        }
    }
}

@Composable
private fun RouterSetupLicenseChoice(accepted: Boolean, enabled: Boolean, licenseGeneration: Long,
    hideAcceptedChoice: Boolean = false, onChange: (Boolean) -> Unit) {
    var expanded by remember(licenseGeneration) { mutableStateOf(false) }
    if (!hideAcceptedChoice || !accepted) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.router_setup_license_title))
        }
    }
    if (expanded) {
        VpnBottomSheet(onDismiss = { expanded = false },
            confineContentGestures = true, footerContent = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    RouterLicenseAcceptedRow(accepted, enabled, onChange)
                }
            }) {
            VpnRouterSheetContent(R.string.router_setup_license_title, R.drawable.lin_info) {
                Text(stringResource(R.string.router_setup_license_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RouterLicenseAcceptedRow(accepted: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    VpnRouterTableRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(
        value = accepted, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Icon(painterResource(R.drawable.lin_info), null, Modifier.size(20.dp))
        Text(stringResource(R.string.router_setup_license_accept), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = accepted, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RouterLocalRoutingChoice(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    VpnRouterTableRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(
        value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp))
        VpnHelpLabel(stringResource(R.string.router_setup_all_lan_vpn), R.string.router_setup_all_lan_vpn,
            R.string.router_setup_all_lan_vpn_help, Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RouterAutoUpdateChoice(options: RouterWizardOptions, enabled: Boolean, onChange: (Boolean) -> Unit) {
    VpnRouterTableRow(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(
        value = options.autoUpdateEnabled, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Icon(painterResource(R.drawable.lin_download), null, Modifier.size(20.dp))
        Text(stringResource(R.string.vpn_router_setup_auto_updates), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = options.autoUpdateEnabled, onCheckedChange = null, enabled = enabled)
    }
    Text(stringResource(R.string.router_updates_scope), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.router_updates_auto_help), style = MaterialTheme.typography.bodySmall)
}

internal fun knownRouterHomeCountry(code: String?): String? = code?.uppercase(Locale.ROOT)?.takeIf { it in Locale.getISOCountries() }

@Composable
private fun RouterHelpLabel(title: Int, help: Int) {
    VpnRouterTableRow(Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(20.dp))
        VpnHelpLabel(stringResource(title), title, help, Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RouterCheckRow(label: Int, help: Int, checked: Boolean, enabled: Boolean,
    showHelp: Boolean = true, onChange: (Boolean) -> Unit) {
    VpnRouterTableRow(Modifier.fillMaxWidth()) {
        val icon = when (label) {
            R.string.vpn_router_auto_home -> R.drawable.lin_router
            R.string.router_review_direct_ip -> R.drawable.router_review_globe
            R.string.router_review_phone_profile -> R.drawable.lin_profiles
            else -> R.drawable.lin_power
        }
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        if (showHelp) VpnHelpLabel(stringResource(label), label, help, Modifier.weight(1f))
        else Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        VpnSwitch(checked, onCheckedChange = onChange, enabled = enabled)
    }
    HorizontalDivider(color = LocalVpnColors.current.border.copy(alpha = .5f))
}

@Composable
private fun RouterFullButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
        border = BorderStroke(1.5.dp, routerConnectColor()),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = routerConnectColor())) {
        Text(stringResource(label))
    }
}

internal fun routerWizardErrorLabel(error: RouterWizardError): Int = when (error) {
    RouterWizardError.WIFI -> R.string.vpn_router_error_wifi
    RouterWizardError.VPN_ACTIVE -> R.string.vpn_router_error_active_vpn
    RouterWizardError.HOME_VPN -> R.string.vpn_router_error_home_vpn
    RouterWizardError.LOCAL_NETWORK_PERMISSION -> R.string.vpn_router_error_local_network_permission
    RouterWizardError.SSH_UNAVAILABLE -> R.string.vpn_router_error_ssh
    RouterWizardError.ADDRESS -> R.string.vpn_router_error_address
    RouterWizardError.IDENTITY -> R.string.vpn_router_error_identity
    RouterWizardError.PASSWORD -> R.string.vpn_router_error_password
    RouterWizardError.PIN -> R.string.vpn_router_error_pin
    RouterWizardError.LOCKED -> R.string.vpn_router_error_locked
    RouterWizardError.UNSUPPORTED -> R.string.vpn_router_error_unsupported
    RouterWizardError.STORAGE -> R.string.vpn_router_error_storage
    RouterWizardError.INSTALL -> R.string.vpn_router_error_install
    RouterWizardError.NETWORK -> R.string.vpn_router_error_network
    RouterWizardError.SAVE -> R.string.vpn_router_error_save
    RouterWizardError.PROFILE -> R.string.vpn_router_error_profile
    RouterWizardError.PROFILE_EXPORT -> R.string.vpn_error_export_profile
    RouterWizardError.UNAVAILABLE -> R.string.vpn_router_error_unavailable
    RouterWizardError.LICENSE -> R.string.router_setup_license_required
    RouterWizardError.LIMIT -> R.string.vpn_router_error_limit
}

@Composable
private fun RouterInstallationProgress(progress: RouterInstallProgress) {
    val percent = progress.percent
    if (percent == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    else key(progress.stage, progress.totalBytes) {
        val displayed by animateFloatAsState(percent / 100f, VpnSheetMotion.fastEffectsSpec(), label = "router-install-progress")
        LinearProgressIndicator(progress = { displayed }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.router_review_progress_percent, percent), style = MaterialTheme.typography.labelLarge)
    }
    Text(stringResource(when (progress.stage) {
        RouterInstallProgressStage.DOWNLOAD -> if (progress.viaDevice) R.string.router_updates_device_download else R.string.router_review_progress_download
        RouterInstallProgressStage.UPLOAD -> R.string.router_review_progress_upload
        RouterInstallProgressStage.INSTALL -> R.string.router_review_progress_install
        RouterInstallProgressStage.CHECK -> R.string.router_review_progress_check
    }), style = MaterialTheme.typography.bodyMedium)
}

private fun wizardAutoUpdateFailureLabel(reason: RouterUpdateFailureReason): Int = when (reason) {
    RouterUpdateFailureReason.UNSUPPORTED_TARGET -> R.string.router_updates_unsupported
    RouterUpdateFailureReason.NOT_CHECKED -> R.string.router_updates_not_checked
    RouterUpdateFailureReason.RELEASE_UNAVAILABLE -> R.string.router_updates_release_unavailable
    RouterUpdateFailureReason.AUTHENTICATION_FAILED -> R.string.router_updates_auth_failed
    RouterUpdateFailureReason.STALE_PLAN, RouterUpdateFailureReason.STALE_SESSION -> R.string.router_updates_stale
    RouterUpdateFailureReason.BUSY -> R.string.router_engines_busy
    RouterUpdateFailureReason.NO_SPACE -> R.string.router_updates_no_space
    RouterUpdateFailureReason.DOWNLOAD_FAILED -> R.string.router_updates_download_failed
    RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED, RouterUpdateFailureReason.DOWNGRADE_REJECTED,
    RouterUpdateFailureReason.VERSION_IDENTITY_CONFLICT -> R.string.router_updates_verification_failed
    RouterUpdateFailureReason.ROLLBACK_UNAVAILABLE -> R.string.router_updates_rollback_unavailable
    RouterUpdateFailureReason.RAM_STAGE_UNAVAILABLE -> R.string.router_updates_ram_unavailable
    RouterUpdateFailureReason.ROUTER_UNAVAILABLE -> R.string.router_updates_unreachable
    RouterUpdateFailureReason.RECOVERY_REQUIRED -> R.string.router_engines_recovery
    RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE -> R.string.router_updates_disconnect_before_update
    RouterUpdateFailureReason.INVALID_REQUEST -> R.string.router_updates_invalid_response
}


@Composable
private fun RouterWizardContent(
    title: Int, icon: Int, address: String, packageVersion: String?, busy: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    titleHelp: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
        val compact = maxHeight < 420.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End) {
                if (busy) {
                    val working = stringResource(R.string.vpn_working)
                    CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
                }
                actions()
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().then(LocalVpnSheetBodyGestures.current)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(painterResource(R.drawable.ic_launcher_openwrt), null,
                        Modifier.size(if (compact) 48.dp else 68.dp), contentScale = ContentScale.Fit)
                    Text(stringResource(R.string.router_login_brand), Modifier.fillMaxWidth(),
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center, maxLines = 2)
                    Text(if (packageVersion == null) stringResource(R.string.router_login_version_unknown)
                        else stringResource(R.string.router_login_version, packageVersion), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Text(address, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(icon), null, Modifier.size(20.dp))
                        Text(stringResource(title), Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (titleHelp != null) VpnRouterHelpButton(title, titleHelp, actionStyle = true)
                    }
                }
                content()
            }
            if (footer != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(LocalVpnSheetBodyGestures.current),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

@Composable
internal fun VpnRouterWizardNavigationFooter(backEnabled: Boolean, onBack: (() -> Unit)?, onClose: (() -> Unit)?) {
    if (onClose != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) OutlinedButton(onClick = onBack, enabled = backEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.router_review_back))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.vpn_cancel))
            }
        }
    } else if (onBack != null) OutlinedButton(onClick = onBack, enabled = backEnabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.router_review_back))
    }
}
