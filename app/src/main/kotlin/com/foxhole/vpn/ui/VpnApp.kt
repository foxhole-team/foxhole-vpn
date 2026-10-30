package com.foxhole.vpn.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.core.importer.PortableProfileExportFailure
import com.foxhole.vpn.ProtocolChoiceAction
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.shareVpnProfile
import com.foxhole.vpn.profileRemovalCurrent
import com.foxhole.vpn.protocolChangeCurrent
import com.foxhole.vpn.protocolChangeRequest
import com.foxhole.vpn.protocolChoiceAction
import com.foxhole.vpn.readProfileClipboard
import com.foxhole.vpn.updates.VpnDataUpdateState
import com.foxhole.vpn.appupdates.VpnAppUpdateState
import com.foxhole.vpn.router.RouterWizardCoordinator
import com.foxhole.vpn.router.RouterWizardPath
import com.foxhole.vpn.router.RouterWizardStage
import com.foxhole.vpn.router.RouterControlCoordinator
import com.foxhole.vpn.router.RouterBetaNoticePreferences
import com.foxhole.vpn.router.RouterProfileSelection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal data class VpnUiActions(
    val connect: () -> Unit,
    val chooseFile: () -> Unit,
    val systemSettings: () -> Unit,
    val consumeFile: () -> Unit,
    val updateData: () -> Unit = {},
    val chooseQr: () -> Unit = {},
    val consumeError: () -> Unit = {},
    val checkAppUpdate: () -> Unit = {},
    val appUpdateAction: () -> Unit = {},
    val consumeAppUpdate: () -> Unit = {},
)

@Composable
internal fun VpnApp(
    controller: VpnController,
    state: VpnUiState,
    settings: VpnSettings,
    actions: VpnUiActions,
    importedFileText: String?,
    externalError: String?,
    dataUpdate: VpnDataUpdateState = VpnDataUpdateState(),
    appUpdate: VpnAppUpdateState = VpnAppUpdateState(),
    openAppUpdate: Boolean = false,
    routerWizard: RouterWizardCoordinator? = null,
    routerProfileProbe: com.foxhole.vpn.router.RouterProfileAutoProbe? = null,
    onRouterEntryRequest: ((() -> Unit) -> Unit)? = null,
    routerControl: RouterControlCoordinator? = null,
    onRouterHomeEnableRequest: ((() -> Unit) -> Unit)? = null,
    exportRouterProfile: (suspend (String) -> RouterProfileSelection?)? = null,
    openRouterSetup: ((String) -> Unit)? = null,
) {
    VpnNoticeScope { VpnAppContent(controller, state, settings, actions, importedFileText, externalError, dataUpdate, appUpdate, openAppUpdate, routerWizard, routerProfileProbe, onRouterEntryRequest, routerControl, onRouterHomeEnableRequest, exportRouterProfile, openRouterSetup) }
}

@Composable
private fun VpnAppContent(
    controller: VpnController,
    state: VpnUiState,
    settings: VpnSettings,
    actions: VpnUiActions,
    importedFileText: String?,
    externalError: String?,
    dataUpdate: VpnDataUpdateState,
    appUpdate: VpnAppUpdateState,
    openAppUpdate: Boolean,
    routerWizard: RouterWizardCoordinator?,
    routerProfileProbe: com.foxhole.vpn.router.RouterProfileAutoProbe?,
    onRouterEntryRequest: ((() -> Unit) -> Unit)?,
    routerControl: RouterControlCoordinator?,
    onRouterHomeEnableRequest: ((() -> Unit) -> Unit)?,
    exportRouterProfile: (suspend (String) -> RouterProfileSelection?)?,
    openRouterSetup: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val scope = rememberCoroutineScope()
    val notices = requireNotNull(LocalVpnNotices.current)
    val navigation = remember { VpnSheetNavigation() }
    val betaNoticePreferences = remember(context.applicationContext) { RouterBetaNoticePreferences(context) }
    var routerBetaNoticeVisible by rememberSaveable { mutableStateOf(false) }
    var profilesUi by remember { mutableStateOf(VpnProfilesUiState()) }
    var importText by remember { mutableStateOf("") }
    var pendingInsecureInput by remember { mutableStateOf<String?>(null) }
    var clipboardRequest by remember { mutableStateOf<ClipboardImportRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    val routerState = routerWizard?.let { it.state.collectAsStateWithLifecycle().value }
    val profileProbeState = routerProfileProbe?.state?.collectAsStateWithLifecycle()?.value
    val routerControlState = routerControl?.let { it.state.collectAsStateWithLifecycle().value }
    LaunchedEffect(routerControl) { routerControl?.loadPairingState() }
    var routerJob by remember(routerWizard) { mutableStateOf<Job?>(null) }
    DisposableEffect(routerWizard) { onDispose { routerJob?.cancel(); routerWizard?.close() } }
    DisposableEffect(routerControl) { onDispose { routerControl?.close() } }
    LaunchedEffect(routerState?.error) {
        routerState?.error?.let { notices.show(resources.getString(routerWizardErrorLabel(it)), error = true) }
    }
    LaunchedEffect(routerState?.stage) {
        if (routerState?.stage == RouterWizardStage.NEW_PIN) routerControl?.loadPairingState()
        if (routerState?.stage == RouterWizardStage.COMPLETE && navigation.current?.page == VpnSheetPage.RouterWizard && routerControl != null) {
            val completedEntryId = navigation.current?.id
            routerWizard?.close()
            routerControl.loadPairingState()
            if (navigation.current?.id == completedEntryId) navigation.replace(VpnSheetPage.RouterControl)
        }
    }
    fun routerAction(secret: CharArray? = null, action: suspend () -> Unit) {
        if (routerWizard == null || routerJob?.isActive == true) { secret?.fill('\u0000'); return }
        routerJob = scope.launch {
            try { action() }
            finally { secret?.fill('\u0000') }
        }.also { job -> job.invokeOnCompletion { secret?.fill('\u0000') } }
    }
    LaunchedEffect(openAppUpdate) {
        if (openAppUpdate) {
            clipboardRequest = null
            pendingInsecureInput = null
            navigation.open(VpnSheetPage.AppUpdate)
            actions.consumeAppUpdate()
        }
    }

    fun canEditProfiles(): Boolean {
        val current = controller.state.value
        return !busy && current.isReady && !current.canDisconnect && !current.isConnected
    }

    fun canImportProfiles(): Boolean = !busy && controller.state.value.isReady

    fun entryCurrent(entry: VpnSheetEntry): Boolean = navigation.current?.id == entry.id

    fun back(entry: VpnSheetEntry) {
        if (navigation.back(entry.id)) {
            if (entry.page == VpnSheetPage.RouterWizard) { routerJob?.cancel(); routerWizard?.close() }
            if (entry.page == VpnSheetPage.RouterControl) routerControl?.close()
            if (entry.page == VpnSheetPage.ImportSources || entry.page == VpnSheetPage.InsecureConsent) clipboardRequest = null
            if (entry.page == VpnSheetPage.InsecureConsent) pendingInsecureInput = null
            if (navigation.current == null) { importText = ""; pendingInsecureInput = null; profilesUi = VpnProfilesUiState() }
        }
    }

    fun close(entry: VpnSheetEntry) {
        if (navigation.current?.id == entry.id) {
            if (entry.page == VpnSheetPage.RouterWizard || entry.page == VpnSheetPage.RouterEntry) { routerJob?.cancel(); routerWizard?.close() }
            if (entry.page == VpnSheetPage.RouterControl) routerControl?.close()
            navigation.close(entry.id)
            profilesUi = VpnProfilesUiState()
            importText = ""
            pendingInsecureInput = null
            clipboardRequest = null
        }
    }

    suspend fun inspectRouterAddress(address: String) {
        val wizard = routerWizard ?: return
        wizard.inspectAddress(address)
        val inspected = wizard.state.value
        if (inspected.stage == RouterWizardStage.IDENTITY && inspected.error == null && !inspected.busy) {
            if (inspected.identityChanged) {
                notices.show(resources.getString(R.string.vpn_router_error_identity), error = true)
                navigation.current?.takeIf { it.page == VpnSheetPage.RouterWizard }?.let(::back)
            } else wizard.acceptIdentity()
        }
    }
    fun continueRouterDiscovery(entry: VpnSheetEntry, reset: Boolean?) {
        val wizard = routerWizard ?: return
        if (!entryCurrent(entry)) return
        requestRouterAccessForEntry({ entryCurrent(entry) }, onRouterEntryRequest,
            { notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true) }) { routerAction {
            if (reset == null) wizard.installDiscovered() else wizard.connectDiscovered(reset)
            val found = wizard.state.value
            if (entryCurrent(entry) && found.error == null && found.stage != RouterWizardStage.ENTRY) {
                navigation.push(VpnSheetPage.RouterWizard)
            }
        } }
    }
    fun openImport() {
        if (!canImportProfiles()) return
        importText = ""
        pendingInsecureInput = null
        clipboardRequest = null
        notices.clearTransient()
        navigation.push(VpnSheetPage.ImportText)
    }

    fun acceptResult(result: VpnImportResult, expectedEntryId: Long, importRequest: Boolean = true, input: String? = null) {
        val currentEntry = navigation.current?.id == expectedEntryId
        when (result) {
            is VpnImportResult.Success -> {
                notices.show(resources.getString(R.string.vpn_import_result, result.added, result.updated, result.rejected), error = result.rejected > 0)
                if (currentEntry && importRequest && input == importText) { importText = ""; pendingInsecureInput = null }
                if (currentEntry && navigation.current?.page != VpnSheetPage.Profiles) navigation.open(VpnSheetPage.Profiles)
            }
            is VpnImportResult.Failure -> notices.show(result.message, error = true)
            VpnImportResult.NeedsInsecureTlsConsent -> {
                if (currentEntry && importRequest && input == importText) {
                    clipboardRequest = null
                    pendingInsecureInput = input
                    navigation.push(VpnSheetPage.InsecureConsent)
                } else if (!importRequest) notices.show(resources.getString(R.string.vpn_error_subscription_failed), error = true)
            }
        }
    }

    fun profileAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notices.show(resources.getString(R.string.vpn_error_action_failed), error = true) }
            finally { busy = false }
        }
    }

    fun clipboardOwnerCurrent(request: ClipboardImportRequest): Boolean =
        clipboardRequest?.token === request.token && clipboardRequest?.input == request.input &&
            navigation.current?.id == request.ownerEntryId && navigation.current?.page == VpnSheetPage.ImportSources

    fun acceptClipboardResult(result: VpnImportResult, request: ClipboardImportRequest) {
        val current = clipboardOwnerCurrent(request)
        when (result) {
            is VpnImportResult.Success -> {
                notices.show(resources.getString(R.string.vpn_import_result, result.added, result.updated, result.rejected), error = result.rejected > 0)
                if (current) { clipboardRequest = null; pendingInsecureInput = null; navigation.open(VpnSheetPage.Profiles) }
            }
            is VpnImportResult.Failure -> {
                notices.show(result.message, error = true)
                if (current) clipboardRequest = null
            }
            VpnImportResult.NeedsInsecureTlsConsent -> if (current) {
                pendingInsecureInput = request.input
                navigation.push(VpnSheetPage.InsecureConsent)
            }
        }
    }

    fun importClipboardRequest(request: ClipboardImportRequest, allowInsecure: Boolean) {
        val input = request.input ?: return
        if (!clipboardOwnerCurrent(request) || !canImportProfiles()) return
        profileAction {
            try { acceptClipboardResult(controller.importProfiles(input, allowInsecure), request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (clipboardOwnerCurrent(request)) clipboardRequest = null
                notices.show(resources.getString(R.string.vpn_error_import_failed), error = true)
            }
        }
    }

    fun pasteClipboard(entry: VpnSheetEntry, view: View) {
        if (!entryCurrent(entry) || entry.page != VpnSheetPage.ImportSources || !canImportProfiles()) return
        val request = ClipboardImportRequest(entry.id)
        clipboardRequest = request
        pendingInsecureInput = null
        notices.clearTransient()
        profileAction {
            if (!awaitClipboardWindowFocus(view)) {
                if (clipboardOwnerCurrent(request)) {
                    clipboardRequest = null
                    notices.show(resources.getString(R.string.vpn_clipboard_focus_unavailable), error = true)
                }
                return@profileAction
            }
            if (!clipboardOwnerCurrent(request)) return@profileAction
            val input = readProfileClipboard(context)
            if (input == null) {
                clipboardRequest = null
                notices.show(resources.getString(R.string.vpn_clipboard_empty), error = true)
                return@profileAction
            }
            val captured = request.copy(input = input)
            clipboardRequest = captured
            try { acceptClipboardResult(controller.importProfiles(input, false), captured) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (clipboardOwnerCurrent(captured)) clipboardRequest = null
                notices.show(resources.getString(R.string.vpn_error_import_failed), error = true)
            }
        }
    }

    fun importProfile(allowInsecure: Boolean, input: String = importText) {
        if (navigation.current?.page != VpnSheetPage.ImportText || !canImportProfiles() || input.isBlank() || input != importText) return
        val entryId = requireNotNull(navigation.current).id
        profileAction {
            try { acceptResult(controller.importProfiles(input, allowInsecure), entryId, input = input) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notices.show(resources.getString(R.string.vpn_error_import_failed), error = true) }
        }
    }

    LaunchedEffect(importedFileText) {
        if (importedFileText != null) {
            importText = importedFileText
            pendingInsecureInput = null
            clipboardRequest = null
            navigation.current?.takeIf { it.page == VpnSheetPage.InsecureConsent }?.let { navigation.back(it.id) }
            if (navigation.current?.page == VpnSheetPage.ImportText) {
                navigation.replace(VpnSheetPage.ImportText)
            } else {
                if (navigation.current == null) navigation.open(VpnSheetPage.ImportSources)
                navigation.push(VpnSheetPage.ImportText)
            }
            actions.consumeFile()
        }
    }
    LaunchedEffect(externalError) {
        if (!externalError.isNullOrBlank()) {
            notices.show(externalError, error = true)
            actions.consumeError()
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message
        if (message.isNullOrBlank()) notices.clearRuntime() else notices.show(message, error = true, persistent = true, runtime = true)
    }
    LaunchedEffect(state, navigation.current?.id) {
        val entry = navigation.current ?: return@LaunchedEffect
        val current = controller.state.value
        val valid = when (val page = entry.page) {
            is VpnSheetPage.ProtocolConfirmation -> protocolChangeCurrent(page.request, current)
            is VpnSheetPage.RemoveConfirmation -> profileRemovalCurrent(current, page.profileId, page.profileRevision)
            else -> true
        }
        if (!valid) back(entry)
    }

    Surface(Modifier.fillMaxSize(), color = LocalVpnColors.current.background, contentColor = LocalVpnColors.current.foreground) {
        VpnNoticeWindow()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) { VpnBrand() }
                IconButton(onClick = { if (canImportProfiles()) { clipboardRequest = null; pendingInsecureInput = null; navigation.open(VpnSheetPage.ImportSources) } },
                    enabled = !busy && state.isReady) {
                    Icon(painterResource(R.drawable.lin_plus), stringResource(R.string.vpn_no_profile))
                }
                if (routerWizard != null || routerControl != null) IconButton(onClick = {
                    clipboardRequest = null; pendingInsecureInput = null
                    if (routerControlState?.selectedRouterId == null) routerWizard?.begin(RouterWizardPath.PAIR, null)
                    routerBetaNoticeVisible = !betaNoticePreferences.isSuppressed()
                    navigation.open(if (routerControlState?.selectedRouterId != null)
                        VpnSheetPage.RouterControl else VpnSheetPage.RouterEntry)
                }) { Icon(painterResource(R.drawable.lin_router), stringResource(R.string.vpn_router)) }
                IconButton(onClick = { clipboardRequest = null; pendingInsecureInput = null; navigation.open(VpnSheetPage.Settings) }) {
                    Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.vpn_settings))
                }
            }
            VpnHome(state,
                onPrimary = {
                    notices.dismiss()
                    val current = controller.state.value
                    if (primaryActionEnabled(current)) when (primaryAction(current)) {
                        VpnPrimaryAction.DISCONNECT -> controller.disconnect()
                        VpnPrimaryAction.CONNECT -> actions.connect()
                    }
                },
                onProfiles = {
                    clipboardRequest = null
                    pendingInsecureInput = null
                    if (controller.state.value.profiles.isEmpty()) {
                        if (canImportProfiles()) navigation.open(VpnSheetPage.ImportSources)
                    } else navigation.open(VpnSheetPage.Profiles)
                },
                modifier = Modifier.weight(1f), dataUpdate = dataUpdate, onDataUpdate = actions.updateData,
                claimConnectionCue = controller::claimConnectionCue,
                appUpdate = appUpdate, onAppUpdate = actions.appUpdateAction)
        }
    }

    navigation.current?.let { shown ->
        val routerSheet = shown.page in setOf(VpnSheetPage.RouterEntry, VpnSheetPage.RouterWizard, VpnSheetPage.RouterControl)
        val showRouterBetaNotice = routerSheet && routerBetaNoticeVisible
        key(showRouterBetaNotice) { VpnBottomSheet(
            onDismiss = {
                if (showRouterBetaNotice) { if (entryCurrent(shown)) routerBetaNoticeVisible = false } else close(shown)
            },
            onBack = if (!showRouterBetaNotice && shown.depth > 0) ({ back(shown) }) else null,
            fixedHeightFraction = when {
                showRouterBetaNotice -> null
                shown.page == VpnSheetPage.RouterEntry -> .85f
                routerSheet -> if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 1f else .75f
                else -> null
            },
            showCloseButton = showRouterBetaNotice || shown.page !in setOf(VpnSheetPage.RouterControl, VpnSheetPage.RouterWizard),
            confineContentGestures = routerSheet && !showRouterBetaNotice,
            footerContent = if (showRouterBetaNotice) ({ VpnRouterBetaNoticeOptOut(betaNoticePreferences) }) else null) {
            val sheetClose = LocalVpnSheetClose.current
            val sheetFocus = LocalFocusManager.current
            val sheetKeyboard = LocalSoftwareKeyboardController.current
            val sheetView = LocalView.current
            LaunchedEffect(shown.id) {
                if (shown.page != VpnSheetPage.ImportText) {
                    sheetFocus.clearFocus(force = true)
                    sheetKeyboard?.hide()
                }
            }
            if (showRouterBetaNotice) VpnRouterBetaNoticeContent()
            else Box(if (routerSheet) Modifier.fillMaxSize() else Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f)) {
                VpnSheetPageTransition(shown, animateSize = !routerSheet) { entry ->
                    val pageBusy = busy || !entryCurrent(entry)
                    when (val page = entry.page) {
                        VpnSheetPage.RouterControl -> if (routerControl != null) VpnRouterControlSheet(routerControl,
                            profileProbe = profileProbeState,
                            onRetryPairing = {
                                if (entryCurrent(entry) && routerWizard != null) {
                                    if (!routerWizard.hasPhysicalWifi()) notices.show(resources.getString(R.string.router_profile_pair_wifi), error = true)
                                    else requestRouterAccessForEntry({ entryCurrent(entry) }, onRouterEntryRequest,
                                        { notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true) }) {
                                        routerControl.close()
                                        routerWizard.beginPairingRetry()
                                        navigation.push(VpnSheetPage.RouterWizard)
                                    }
                                }
                            },
                            onPair = { if (entryCurrent(entry)) {
                                routerWizard?.begin(RouterWizardPath.PAIR, null)
                                navigation.replace(VpnSheetPage.RouterEntry)
                            } },
                            onAddRouter = { if (entryCurrent(entry) && routerWizard != null && routerControl.state.value.canAddRouter) {
                                routerControl.close()
                                routerWizard.begin(RouterWizardPath.PAIR, null, addingNew = true)
                                navigation.replace(VpnSheetPage.RouterEntry)
                            } },
                            onUnpaired = { if (entryCurrent(entry) && routerControl.state.value.selectedRouterId == null) {
                                routerWizard?.begin(RouterWizardPath.PAIR, null)
                                navigation.replace(VpnSheetPage.RouterEntry)
                            } },
                            requestAccess = { action -> requestRouterAccessForEntry({ entryCurrent(entry) }, onRouterEntryRequest,
                                { notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true) }, action)
                            }, requestHomeEnable = { action -> if (entryCurrent(entry)) {
                                if (onRouterHomeEnableRequest == null) notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true)
                                else onRouterHomeEnableRequest { if (entryCurrent(entry)) action() }
                            } }, enabled = entryCurrent(entry))
                        VpnSheetPage.RouterEntry -> if (routerWizard != null) VpnRouterEntrySheet(
                            setupEnabled = routerWizard.setupAvailable,
                            installationCatalog = routerWizard.installationCatalog,
                            discovery = routerState?.discovery,
                            finding = routerState?.busy == true,
                            onFind = { if (entryCurrent(entry) && !routerWizard.hasPhysicalWifi()) {
                                notices.show(resources.getString(R.string.vpn_router_error_wifi), error = true)
                            } else requestRouterAccessForEntry({ entryCurrent(entry) }, onRouterEntryRequest,
                                { notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true) }) {
                                    routerWizard.begin(RouterWizardPath.PAIR, null, addingNew = routerWizard.isAddingRouter, keepNotFoundPresentation = true)
                                    routerAction { routerWizard.findRouter() }
                                }
                            },
                            onSetup = { continueRouterDiscovery(entry, null) },
                            onPair = { if (entryCurrent(entry)) {
                                routerWizard.begin(RouterWizardPath.PAIR, null, addingNew = routerWizard.isAddingRouter)
                                navigation.push(VpnSheetPage.RouterWizard)
                            } },
                            onConnect = { reset -> continueRouterDiscovery(entry, reset) })
                        VpnSheetPage.RouterWizard -> if (routerWizard != null && routerState != null) VpnRouterWizardSheet(
                            routerState, state.profiles, routerWizard.supportsHomeAutodetection, routerWizard.supportsRemoteProfile, routerWizard.installationCatalog,
                            RouterWizardActions(
                                inspect = { address -> requestRouterAccessForEntry({ entryCurrent(entry) }, onRouterEntryRequest,
                                    { notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true) }) {
                                    routerAction { inspectRouterAddress(address) }
                                } },
                                trust = { if (entryCurrent(entry)) routerAction { routerWizard.acceptIdentity() } },
                                installProtocols = { if (entryCurrent(entry)) routerWizard.installProtocols(it) },
                                installCompactSelection = { protocols, server -> if (entryCurrent(entry)) routerWizard.installProtocols(protocols, server) },
                                confirmInstallProtocols = { if (entryCurrent(entry)) routerAction { routerWizard.confirmInstallProtocols() } },
                                login = { credential -> if (entryCurrent(entry)) {
                                    val ownedCredential = credential.copyOf()
                                    routerAction(ownedCredential) { routerWizard.login(ownedCredential) }
                                } else credential.fill('\u0000') },
                                newPin = { credential -> if (entryCurrent(entry)) routerAction(credential) { routerWizard.changePin(credential) } else credential.fill('\u0000') },
                                install = { if (entryCurrent(entry)) routerAction { routerWizard.install() } },
                                options = { if (entryCurrent(entry)) routerWizard.options(it) },
                                finish = { if (entryCurrent(entry)) routerAction { routerWizard.finish() } },
                                failure = { notices.show(resources.getString(R.string.vpn_router_error_network), error = true) },
                                openRouterSetup = {
                                    val address = routerState.address
                                    if (entryCurrent(entry) && address == "192.168.1.1") {
                                        try {
                                            if (openRouterSetup != null) openRouterSetup(address)
                                            else context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("http://192.168.1.1/cgi-bin/luci/admin/services/foxhole/pin")))
                                        }
                                        catch (_: Exception) { notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true) }
                                    }
                                },
                            ), onBack = { back(entry) })
                        VpnSheetPage.Settings -> VpnSettingsScreen(settings,
                            onSettingsDetail = { if (entryCurrent(entry)) navigation.push(VpnSheetPage.SettingsDetail(it)) },
                            onProfiles = { if (entryCurrent(entry)) navigation.push(VpnSheetPage.Profiles) },
                            onSystemSettings = { if (entryCurrent(entry)) actions.systemSettings() },
                            onAppUpdate = { if (entryCurrent(entry)) navigation.push(VpnSheetPage.AppUpdate) })
                        VpnSheetPage.AppUpdate -> VpnAppUpdateSheet(appUpdate,
                            onAction = { if (entryCurrent(entry)) actions.appUpdateAction() },
                            onCheck = { if (entryCurrent(entry)) actions.checkAppUpdate() })
                        is VpnSheetPage.SettingsDetail -> VpnSettingsDetailScreen(page.detail, settings,
                            onSettings = { updated -> if (entryCurrent(entry)) profileAction {
                                if (page.detail == VpnSettingsDetail.LANGUAGE) {
                                    check(com.foxhole.vpn.selectVpnAppLanguage(context, controller, updated.language)) { "App language change remains pending" }
                                } else com.foxhole.vpn.updateVpnAppSettings(controller, updated)
                            } },
                            onBack = { back(entry) }, enabled = !pageBusy, busy = busy)
                        VpnSheetPage.Profiles -> VpnProfilesSheet(state, busy, profilesUi,
                            onAdd = { if (entryCurrent(entry) && canImportProfiles()) { clipboardRequest = null; pendingInsecureInput = null; navigation.push(VpnSheetPage.ImportSources) } },
                            onSelect = { if (entryCurrent(entry) && canEditProfiles()) controller.selectProfile(it) },
                            onActions = { profile -> if (entryCurrent(entry) && !busy && controller.state.value.isReady) {
                                navigation.push(VpnSheetPage.ProfileActions(profile.id))
                            } },
                            onSelectProtocol = { profile, option ->
                                if (entryCurrent(entry) && !busy) when (protocolChoiceAction(controller.state.value, profile, option)) {
                                    ProtocolChoiceAction.IMMEDIATE -> profileAction { controller.selectProtocol(profile, option) }
                                    ProtocolChoiceAction.CONFIRM -> protocolChangeRequest(controller.state.value, profile, option)?.let {
                                        navigation.push(VpnSheetPage.ProtocolConfirmation(it))
                                    }
                                    ProtocolChoiceAction.NONE -> Unit
                                }
                            }, interactionAllowed = { entryCurrent(entry) })
                        is VpnSheetPage.ProfileActions -> {
                            val profile = state.profiles.firstOrNull { it.id == page.profileId }
                            if (profile == null) {
                                LaunchedEffect(entry.id) { if (entryCurrent(entry)) back(entry) }
                            } else VpnProfileActionsSheet(profile, pageBusy,
                                shareEnabled = !pageBusy && state.isReady, editable = !pageBusy && canEditProfiles(),
                                routerPaired = routerControlState?.preferences?.pairingId != null,
                                onShare = { if (entryCurrent(entry) && !busy && controller.state.value.isReady) profileAction {
                                    try {
                                        shareVpnProfile(context, requireNotNull(controller.exportProfile(profile.id)))
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: PortableProfileExportFailure) { notices.show(resources.getString(R.string.vpn_error_export_profile), error = true) }
                                    catch (_: Exception) { notices.show(resources.getString(R.string.vpn_error_action_failed), error = true) }
                                } },
                                onSendToRouter = {
                                    if (entryCurrent(entry) && !busy && routerControl != null && controller.state.value.isReady) {
                                        onRouterEntryRequest?.invoke {
                                            if (entryCurrent(entry) && !busy) profileAction {
                                                try {
                                                    val selection = exportRouterProfile?.invoke(profile.id)
                                                    if (selection != null) routerControl.sendSelection(selection)
                                                    else routerControl.sendProfile(profile.name, requireNotNull(controller.exportProfile(profile.id)))
                                                    val failure = routerControl.state.value.error
                                                    if (failure == null) notices.show(resources.getString(R.string.vpn_profile_sent_to_router))
                                                    else notices.show(resources.getString(routerWizardErrorLabel(failure)), error = true)
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: PortableProfileExportFailure) { notices.show(resources.getString(R.string.vpn_error_export_profile), error = true) }
                                                catch (_: Exception) { notices.show(resources.getString(R.string.vpn_error_action_failed), error = true) }
                                            }
                                        } ?: notices.show(resources.getString(R.string.vpn_router_error_unavailable), error = true)
                                    }
                                },
                                onRefresh = { if (entryCurrent(entry) && canEditProfiles() && profile.refreshable) profileAction {
                                    try { acceptResult(controller.refreshSubscription(profile.id), entry.id, importRequest = false) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { notices.show(resources.getString(R.string.vpn_error_subscription_failed), error = true) }
                                } },
                                onRemove = { if (entryCurrent(entry) && canEditProfiles()) {
                                    navigation.push(VpnSheetPage.RemoveConfirmation(profile.id, controller.state.value.profileRevision))
                                } },
                            )
                        }
                        is VpnSheetPage.ProtocolConfirmation -> VpnConfirmationContent(
                            R.string.vpn_protocol_change_title, stringResource(R.string.vpn_protocol_change_body), R.drawable.lin_warning,
                            enabled = !pageBusy && protocolChangeCurrent(page.request, state), onBack = { back(entry) },
                            onContinue = {
                                if (entryCurrent(entry) && !busy && protocolChangeCurrent(page.request, controller.state.value)) {
                                    back(entry)
                                    profileAction { controller.confirmProtocolChange(page.request) }
                                }
                            })
                        is VpnSheetPage.RemoveConfirmation -> VpnConfirmationContent(
                            R.string.vpn_remove_title, stringResource(R.string.vpn_remove_body, state.profiles.firstOrNull { it.id == page.profileId }?.name.orEmpty()),
                            R.drawable.lin_trash, enabled = !pageBusy && profileRemovalCurrent(state, page.profileId, page.profileRevision), onBack = { back(entry) },
                            onContinue = {
                                if (entryCurrent(entry) && canEditProfiles() && profileRemovalCurrent(controller.state.value, page.profileId, page.profileRevision)) {
                                    back(entry)
                                    profileAction { controller.removeProfile(page.profileId, page.profileRevision) }
                                }
                            })
                        VpnSheetPage.ImportSources -> VpnImportSheet(enabled = !pageBusy && state.isReady,
                            onPaste = { pasteClipboard(entry, sheetView) }, onManual = { if (entryCurrent(entry)) openImport() },
                            onFile = { if (entryCurrent(entry) && canImportProfiles()) { close(entry); actions.chooseFile() } },
                            onQr = { if (entryCurrent(entry) && canImportProfiles()) { close(entry); actions.chooseQr() } })
                        VpnSheetPage.ImportText -> VpnSheetContent(R.string.vpn_import, R.drawable.lin_plus, busy = busy) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(importText, onValueChange = { if (entryCurrent(entry) && it.length <= 1024 * 1024) importText = it },
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp), enabled = !pageBusy && state.isReady,
                                        label = { Text(stringResource(R.string.vpn_import_text)) }, minLines = 4, maxLines = 7)
                                    Row {
                                        TextButton(onClick = { if (entryCurrent(entry)) readProfileClipboard(context)?.let { importText = it } }, enabled = !pageBusy && state.isReady) {
                                            Icon(painterResource(R.drawable.lin_clipboard), null, Modifier.size(24.dp)); Spacer(Modifier.size(8.dp))
                                            Text(stringResource(R.string.vpn_import_clipboard))
                                        }
                                        TextButton(onClick = { if (entryCurrent(entry)) actions.chooseFile() }, enabled = !pageBusy && state.isReady) {
                                            Icon(painterResource(R.drawable.lin_file), null, Modifier.size(24.dp)); Spacer(Modifier.size(8.dp))
                                            Text(stringResource(R.string.vpn_import_file))
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { back(entry) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vpn_cancel)) }
                                    Button(onClick = { if (entryCurrent(entry)) importProfile(false) }, enabled = !pageBusy && state.isReady && importText.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                        Text(stringResource(if (busy) R.string.vpn_import_busy else R.string.vpn_import))
                                    }
                                }
                            }
                        }
                        VpnSheetPage.InsecureConsent -> VpnSheetContent(R.string.vpn_insecure_title, R.drawable.lin_warning) {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                    Text(stringResource(R.string.vpn_insecure_body), style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { back(entry) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vpn_cancel)) }
                                    Button(onClick = {
                                        val input = pendingInsecureInput
                                        val clipboard = clipboardRequest
                                        if (entryCurrent(entry) && !busy && input != null && canImportProfiles()) {
                                            if (clipboard != null && clipboard.input == input) {
                                                if (navigation.back(entry.id)) {
                                                    pendingInsecureInput = null
                                                    importClipboardRequest(clipboard, true)
                                                }
                                            } else if (clipboard == null && input == importText) {
                                                back(entry)
                                                importProfile(true, input)
                                            }
                                        }
                                    }, enabled = !pageBusy && pendingInsecureInput != null &&
                                        (if (clipboardRequest != null) pendingInsecureInput == clipboardRequest?.input else pendingInsecureInput == importText) && canImportProfiles(),
                                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                        Text(stringResource(R.string.vpn_insecure_accept))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } }
    }
}

private data class ClipboardImportRequest(val ownerEntryId: Long, val input: String? = null, val token: Any = Any())

private suspend fun awaitClipboardWindowFocus(view: View): Boolean = suspendCancellableCoroutine { continuation ->
    if (view.hasWindowFocus()) { continuation.resume(true); return@suspendCancellableCoroutine }
    val handler = Handler(Looper.getMainLooper())
    var observer = view.viewTreeObserver
    var finished = false
    lateinit var focus: ViewTreeObserver.OnWindowFocusChangeListener
    lateinit var attachment: View.OnAttachStateChangeListener
    lateinit var timeout: Runnable
    fun cleanup() {
        if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focus)
        view.removeOnAttachStateChangeListener(attachment)
        handler.removeCallbacks(timeout)
    }
    fun finish(value: Boolean) {
        if (finished) return
        finished = true
        cleanup()
        if (continuation.isActive) continuation.resume(value)
    }
    focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused && view.hasWindowFocus()) finish(true) }
    attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(attached: View) {
            if (!observer.isAlive) { observer = attached.viewTreeObserver; observer.addOnWindowFocusChangeListener(focus) }
            if (attached.hasWindowFocus()) finish(true)
        }
        override fun onViewDetachedFromWindow(detached: View) { finish(false) }
    }
    timeout = Runnable { finish(false) }
    if (observer.isAlive) observer.addOnWindowFocusChangeListener(focus)
    view.addOnAttachStateChangeListener(attachment)
    // Window focus is an Android event; a test-clock timeout can outrun its delivery.
    handler.postDelayed(timeout, 2_000L)
    continuation.invokeOnCancellation {
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!finished) { finished = true; cleanup() } }
        else handler.post { if (!finished) { finished = true; cleanup() } }
    }
    if (view.hasWindowFocus()) finish(true)
}

@Composable
private fun VpnConfirmationContent(title: Int, body: String, icon: Int, enabled: Boolean, onBack: () -> Unit, onContinue: () -> Unit) {
    VpnSheetContent(title, icon) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vpn_cancel)) }
                Button(onClick = onContinue, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vpn_continue)) }
            }
        }
    }
}
