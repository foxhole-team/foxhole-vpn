package com.foxhole.vpn.ui

import androidx.compose.ui.semantics.clearAndSetSemantics

import androidx.compose.ui.graphics.graphicsLayer

import com.foxhole.vpn.router.routerDisplayCountryCode

import androidx.compose.foundation.layout.Spacer
import com.foxhole.vpn.router.RouterUpdatePollBudget
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.vpn.router.RouterEngineOperation
import com.foxhole.vpn.router.RouterEngineImpact
import com.foxhole.vpn.router.RouterEngineJobState
import com.foxhole.vpn.router.RouterInstallProtocol
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.vpn.R
import com.foxhole.vpn.shareVpnProfile
import com.foxhole.vpn.router.RouterControlCoordinator
import com.foxhole.vpn.router.RouterControlStage
import com.foxhole.vpn.router.RouterManagementLanding
import com.foxhole.vpn.router.RouterWizardError
import com.foxhole.vpn.router.RouterControlState
import com.foxhole.vpn.router.RouterLanDeviceKind
import com.foxhole.vpn.router.routerInstalledProtocols
import com.foxhole.vpn.router.routerLanDeviceKind
import com.foxhole.vpn.router.visibleRouterLanDevices
import com.foxhole.vpn.router.RouterUpdateAvailability
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private data class RouterActionTarget(val id: String, val remote: Boolean = false, val device: Boolean = false)
private enum class ControlPage { LOADING, HUB, ROUTER_VPN, CLIENTS, DEVICE, ACTIONS, REMOVE, UNPAIR, CREATE, PROFILE, SETTINGS, PROTOCOLS, ENGINE_PLAN, ENGINE_JOB, UPDATES }

@Composable
internal fun VpnRouterControlSheet(
    coordinator: RouterControlCoordinator,
    onPair: () -> Unit,
    onUnpaired: () -> Unit = onPair,
    requestAccess: ((() -> Unit) -> Unit),
    requestHomeEnable: ((() -> Unit) -> Unit),
    enabled: Boolean = true,
    onClose: (() -> Unit)? = null,
    onAddRouter: () -> Unit = onPair,
    profileProbe: com.foxhole.vpn.router.RouterProfileProbeState? = null,
    onRetryPairing: () -> Unit = onPair,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val networkGeneration by rememberRouterNetworkGeneration()
    LaunchedEffect(coordinator, networkGeneration) { coordinator.checkHomeVpnTransport() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val context = LocalContext.current
    val notices = requireNotNull(LocalVpnNotices.current)
    val current by rememberUpdatedState(enabled)
    var settledLanding by remember(coordinator, state.selectedRouterId) { mutableStateOf<ControlPage?>(null) }
    val landing = if (state.preferences != null || state.savedRouters.isNotEmpty()) ControlPage.HUB
        else settledLanding ?: ControlPage.LOADING
    var landingResolved by remember(coordinator, state.selectedRouterId) { mutableStateOf(false) }
    var page by remember(coordinator) { mutableStateOf(if (state.preferences != null) ControlPage.HUB else ControlPage.LOADING) }
    var settingsRouterRequested by remember(coordinator) { mutableStateOf<String?>(null) }
    var target by remember(coordinator) { mutableStateOf<RouterActionTarget?>(null) }
    var removalRevision by remember(coordinator) { mutableStateOf<Long?>(null) }
    var unpairId by remember(coordinator) { mutableStateOf<String?>(null) }
    var deletePackage by remember(coordinator) { mutableStateOf(false) }
    var clientName by remember(coordinator) { mutableStateOf("") }
    var qrProfile by remember(coordinator) { mutableStateOf<String?>(null) }
    var devicesExpanded by remember(coordinator) { mutableStateOf(false) }
    var profilesExpanded by remember(coordinator) { mutableStateOf(false) }
    val clientsListState = rememberLazyListState()
    val profilesListState = rememberLazyListState()
    val enginesListState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var engineSelection by remember(coordinator) { mutableStateOf<Set<RouterInstallProtocol>>(emptySet()) }
    var notifiedEngineJob by remember(coordinator) { mutableStateOf<String?>(null) }
    var opened by remember(coordinator, enabled) { mutableStateOf(false) }
    var discoveryPending by remember(coordinator, enabled) { mutableStateOf(false) }
    var discoveryAttempt by remember(coordinator) { mutableStateOf(0) }
    var discoveryToken by remember(coordinator) { mutableStateOf(0L) }
    var consumedDiscoveryAttempt by remember(coordinator) { mutableStateOf(0) }
    var devicesPending by remember(coordinator) { mutableStateOf(false) }
    var devicesFailed by remember(coordinator) { mutableStateOf(false) }
    var devicesAttempt by remember(coordinator) { mutableStateOf(0) }
    var devicesOwner by remember(coordinator) { mutableStateOf<String?>(null) }
    var devicesToken by remember(coordinator) { mutableStateOf(0L) }
    fun retryDiscovery() { discoveryAttempt++ }
    fun requestDevices() {
        devicesOwner = coordinator.state.value.selectedRouterId
        devicesPending = true
        devicesFailed = false
        devicesAttempt++
    }
    fun networkAction(action: suspend () -> Unit) {
        requestAccess { if (current && scope.isActive) scope.launch { action() } }
    }
    fun dashboard() {
        val latest = coordinator.state.value
        page = if (latest.preferences != null || latest.savedRouters.isNotEmpty()) ControlPage.HUB
            else settledLanding ?: ControlPage.LOADING
        target = null
        qrProfile = null
        clientName = ""
    }
    LaunchedEffect(coordinator, enabled, discoveryAttempt, networkGeneration) {
        val token = ++discoveryToken
        if (!enabled) { discoveryPending = false; coordinator.close(); return@LaunchedEffect }
        discoveryPending = false
        val forceLocal = discoveryAttempt > consumedDiscoveryAttempt
        consumedDiscoveryAttempt = discoveryAttempt
        val access = coordinator.prepareDiscovery(forceLocal)
        if (!access.canDiscover) { opened = true; return@LaunchedEffect }
        discoveryPending = true
        val settled = CompletableDeferred<Unit>()
        var task: Job? = null
        var live = true
        try {
            requestAccess {
                if (live && current && scope.isActive && token == discoveryToken && task == null) {
                    task = scope.launch {
                        try {
                            if (discoveryAttempt == 0) coordinator.open() else coordinator.refreshConnectedRouter()
                        } finally { settled.complete(Unit) }
                    }
                }
            }
            val completed = withTimeoutOrNull(45_000) { settled.await(); true } == true
            if (token == discoveryToken && current) {
                if (!completed) coordinator.close()
                opened = true
            }
        } finally {
            live = false; task?.cancel(); settled.cancel()
            if (token == discoveryToken) discoveryPending = false
        }
    }
    LaunchedEffect(coordinator, enabled, devicesAttempt, state.selectedRouterId) {
        val token = ++devicesToken
        val selected = devicesOwner
        if (!enabled || devicesAttempt == 0 || selected == null || selected != state.selectedRouterId) return@LaunchedEffect
        devicesPending = true
        devicesFailed = false
        val settled = CompletableDeferred<Unit>()
        var task: Job? = null
        var live = true
        try {
            requestAccess {
                if (live && current && scope.isActive && token == devicesToken && task == null &&
                    coordinator.state.value.selectedRouterId == selected) {
                    task = scope.launch {
                        try {
                            if (coordinator.state.value.controlsAvailable) coordinator.refreshDevices()
                        } finally { settled.complete(Unit) }
                    }
                }
            }
            val completed = withTimeoutOrNull(45_000) { settled.await(); true } == true
            if (token == devicesToken && coordinator.state.value.selectedRouterId == selected) {
                devicesFailed = !completed || !coordinator.state.value.lanDevicesLoaded
            }
        } finally {
            live = false; task?.cancel(); settled.cancel()
            if (token == devicesToken) devicesPending = false
        }
    }
    LaunchedEffect(state.selectedRouterId) {
        val openSettings = settingsRouterRequested == state.selectedRouterId && state.selectedRouterId != null
        dashboard()
        if (openSettings) page = ControlPage.SETTINGS
        devicesExpanded = false
        devicesPending = false
        devicesFailed = false
        devicesOwner = null
        profilesExpanded = false
        clientsListState.scrollToItem(0)
        profilesListState.scrollToItem(0)
        enginesListState.scrollToItem(0)
        engineSelection = emptySet()
        notifiedEngineJob = null
    }
    DisposableEffect(coordinator) { onDispose { coordinator.close() } }
    DisposableEffect(Unit) { onDispose { qrProfile = null } }
    LaunchedEffect(state.error, state.entryAccess) {
        val cause = if (state.entryAccess == com.foxhole.vpn.router.RouterManagementAccess.VPN_ACTIVE) "VPN_ACTIVE" else state.error?.name
        if (!coordinator.claimUiNotice("connection", cause)) return@LaunchedEffect
        if (state.error == RouterWizardError.VPN_ACTIVE ||
            state.entryAccess == com.foxhole.vpn.router.RouterManagementAccess.VPN_ACTIVE) {
            notices.show(resources.getString(R.string.router_management_disconnect_vpn), error = false)
        } else state.error?.let { notices.show(resources.getString(
            if (state.stage == RouterControlStage.PAIRING_REQUIRED) R.string.router_control_pairing_required
            else routerWizardErrorLabel(it),
        ), error = true) }
    }
    LaunchedEffect(state.stage) {
        if (state.stage in setOf(RouterControlStage.UNPAIRED, RouterControlStage.PAIRING_REQUIRED) &&
            page !in setOf(ControlPage.UNPAIR, ControlPage.UPDATES)) dashboard()
    }
    LaunchedEffect(state.hasConfirmedDirectIp, state.snapshot != null, state.selectedRouterId) {
        if (state.snapshot != null || state.preferences?.publicIpConfirmed == true) {
            settledLanding = ControlPage.HUB
            if (!landingResolved && page == ControlPage.LOADING) {
                dashboard()
                landingResolved = true
            }
        }
    }
    LaunchedEffect(coordinator, opened, enabled, state.stage, state.busy) {
        if (opened && enabled && !discoveryPending && !state.busy && state.stage == RouterControlStage.UNPAIRED && state.savedRouters.isEmpty()) {
            val latest = coordinator.state.value
            if (!latest.busy && latest.stage == RouterControlStage.UNPAIRED && latest.savedRouters.isEmpty()) {
                unpairId = null; onUnpaired()
            }
        }
    }
    LaunchedEffect(coordinator, opened, enabled, state.selectedRouterId, state.stage) {
        if (opened && enabled && state.stage == RouterControlStage.CONNECTED && state.engines.supported == null && !state.updates.running) coordinator.refreshEngines()
    }
    LaunchedEffect(coordinator, opened, enabled, state.selectedRouterId, page) {
        if (opened && enabled && page == ControlPage.SETTINGS &&
            coordinator.state.value.updates.supported == null && coordinator.state.value.stage == RouterControlStage.CONNECTED)
            coordinator.refreshUpdates()
    }
    val updatePollBudget = remember(coordinator, state.selectedRouterId, state.updates.pollEpoch) {
        RouterUpdatePollBudget(selectedRouterId = state.selectedRouterId)
    }
    LaunchedEffect(coordinator, page, enabled, state.selectedRouterId, state.updates.pollEpoch) {
        if (enabled && page == ControlPage.UPDATES && state.updates.pollEpoch > 0) {
            val started = updatePollBudget.begin(android.os.SystemClock.elapsedRealtime())
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() - started < 300_000L) {
                    if (coordinator.state.value.busy) { delay(250); continue }
                    val next = updatePollBudget.next(coordinator.state.value, android.os.SystemClock.elapsedRealtime()) ?: break
                    delay(next.delayMs)
                    try { withTimeout(next.timeoutMs) { coordinator.refreshUpdates(statusOnly = next.statusOnly) } }
                    catch (_: TimeoutCancellationException) { break }
                }
            }
        }
    }
    LaunchedEffect(state.engines.status?.catalogSha256, state.engines.status?.revision) {
        val available = state.engines.status?.entries.orEmpty().filter { it.canAdd }.map { it.protocol }.toSet()
        engineSelection = engineSelection.intersect(available)
    }
    LaunchedEffect(state.engines.failure, state.engines.job?.jobId) {
        state.engines.failure?.let { notices.show(resources.getString(routerEngineFailureLabel(it)), error = true) }
    }
    LaunchedEffect(page, state.engines.job?.jobId, enabled) {
        val id = state.engines.job?.jobId
        if (enabled && page == ControlPage.ENGINE_JOB && id != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val started = android.os.SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive && coordinator.state.value.engines.job?.takeIf { it.jobId == id }?.running == true &&
                android.os.SystemClock.elapsedRealtime() - started < 300_000L) {
                delay(1500)
                coordinator.refreshEngineJob(id)
            }
        }
    }
    LaunchedEffect(page, state.engines.job?.jobId, state.engines.job?.state) {
        val job = state.engines.job
        if (page == ControlPage.ENGINE_JOB && job?.state == RouterEngineJobState.SUCCEEDED && notifiedEngineJob != job.jobId) {
            notifiedEngineJob = job.jobId
            notices.show(resources.getString(R.string.router_engines_done))
        }
    }
    fun back() {
        settingsRouterRequested = null
        if (page == ControlPage.ENGINE_PLAN) coordinator.discardEnginePlan()
        page = when (page) {
            ControlPage.REMOVE -> if (target?.remote == true) ControlPage.PROFILE else ControlPage.ACTIONS
            ControlPage.DEVICE -> ControlPage.ROUTER_VPN
            ControlPage.SETTINGS -> landing
            ControlPage.PROTOCOLS -> ControlPage.SETTINGS
            ControlPage.UPDATES -> ControlPage.SETTINGS
            ControlPage.ENGINE_PLAN, ControlPage.ENGINE_JOB -> ControlPage.PROTOCOLS
            ControlPage.CREATE, ControlPage.PROFILE -> ControlPage.CLIENTS
            ControlPage.ACTIONS -> ControlPage.ROUTER_VPN
            else -> landing
        }
        if (page != ControlPage.PROFILE) qrProfile = null
    }
    BackHandler(enabled && page != landing) { back() }
    val item = target?.let { selected ->
        (if (selected.remote) state.snapshot?.clients else state.snapshot?.servers)?.singleOrNull { it.text("id") == selected.id }
    }
    val device = target?.takeIf { it.device }?.let { selected -> state.lanDevices.singleOrNull { it.id == selected.id } }
    val canAct = enabled && !discoveryPending && state.controlsAvailable
    val discovering = discoveryPending
    val discoveryReady = !discoveryPending && state.stage == RouterControlStage.CONNECTED && state.snapshot != null
    val rootPage = page == landing
    fun openClient(id: String) {
        val owner = coordinator.state.value.selectedRouterId
        target = RouterActionTarget(id, remote = true)
        qrProfile = null
        page = ControlPage.PROFILE
        networkAction {
            coordinator.showRemoteClient(id) { profile ->
                if (current && coordinator.state.value.selectedRouterId == owner && target?.id == id && page == ControlPage.PROFILE) qrProfile = profile
            }
        }
    }
    fun selectRouter(id: String) { settingsRouterRequested = null; if (id != state.selectedRouterId) networkAction { coordinator.selectRouter(id) } }
    fun routerSettings(id: String) {
        settingsRouterRequested = id
        if (id == state.selectedRouterId) page = ControlPage.SETTINGS
        else networkAction {
            coordinator.selectRouter(id)
            if (current && settingsRouterRequested == id && coordinator.state.value.selectedRouterId == id) page = ControlPage.SETTINGS
        }
    }
    fun removeRouter(id: String) {
        settingsRouterRequested = null
        deletePackage = false
        unpairId = id
        page = ControlPage.UNPAIR
    }
    fun renameRouter(id: String, label: String) {
        scope.launch { if (current) coordinator.renameRouter(id, label) }
    }
    val title = if (rootPage) R.string.router_review_management else when (page) {
        ControlPage.UNPAIR -> R.string.router_review_unpair
        ControlPage.CREATE -> R.string.router_review_create_profile
        ControlPage.PROFILE -> R.string.router_control_client_details
        ControlPage.REMOVE -> R.string.router_control_client_remove_title
        ControlPage.ACTIONS -> R.string.vpn_profile_actions_title
        ControlPage.LOADING, ControlPage.HUB -> R.string.router_review_management
        ControlPage.ROUTER_VPN -> R.string.router_review_router_vpn
        ControlPage.CLIENTS -> R.string.router_review_vpn_clients
        ControlPage.DEVICE -> R.string.router_review_device_routing
        ControlPage.UPDATES -> R.string.router_updates_title
        ControlPage.SETTINGS -> R.string.router_review_settings
        ControlPage.PROTOCOLS, ControlPage.ENGINE_PLAN, ControlPage.ENGINE_JOB -> R.string.router_engines_title
    }
    VpnRouterSheetContent(
        title,
        R.drawable.lin_router,
        busy = state.busy,
        titleHelp = if (!rootPage && page !in setOf(ControlPage.UNPAIR, ControlPage.ROUTER_VPN, ControlPage.CLIENTS)) {
            when (page) {
                ControlPage.PROTOCOLS, ControlPage.ENGINE_PLAN, ControlPage.ENGINE_JOB -> R.string.router_engines_help
                ControlPage.UPDATES -> R.string.router_updates_scope
                ControlPage.SETTINGS -> R.string.router_review_settings_help
                ControlPage.CREATE -> R.string.router_control_create_help
                else -> if (target?.remote == true) R.string.router_control_clients_help else R.string.router_control_vpn_help
            }
        } else null,
        actions = {
            if (page in setOf(ControlPage.ROUTER_VPN, ControlPage.CLIENTS)) {
                IconButton(onClick = { page = ControlPage.SETTINGS }, modifier = Modifier.size(48.dp), enabled = enabled && !discoveryPending && !state.busy && state.preferences != null) {
                    Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.router_review_settings), Modifier.size(22.dp))
                }
            }
            if (rootPage) {
                if (state.savedRouters.size == 1) IconButton(
                    onClick = { state.selectedRouterId?.let(::removeRouter) }, modifier = Modifier.size(48.dp),
                    enabled = enabled && state.selectedRouterId != null) {
                    Icon(painterResource(R.drawable.lin_trash), stringResource(R.string.router_review_unpair),
                        Modifier.size(22.dp), tint = vpnDeleteColor())
                }
                IconButton(onClick = onAddRouter, modifier = Modifier.size(48.dp), enabled = enabled && state.canAddRouter) {
                    Icon(painterResource(R.drawable.lin_plus), stringResource(R.string.router_review_add_router), Modifier.size(22.dp))
                }
            }
        },
        scrollable = false,
        footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!discoveryPending && state.stage != RouterControlStage.CONNECTED && (state.stage == RouterControlStage.PAIRING_REQUIRED ||
                        profileProbe?.error in setOf(RouterWizardError.PIN, RouterWizardError.IDENTITY))) {
                    Text(stringResource(R.string.router_profile_probe_failed), modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onRetryPairing, enabled = enabled && !discoveryPending && !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.router_profile_retry_pairing))
                    }
                }
                when (page) {
                    ControlPage.LOADING, ControlPage.HUB -> {
                        val controlsAlpha by animateFloatAsState(if (discovering) 0f else 1f,
                            androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            label = "router-controls-opacity")
                        if (discovering || discoveryReady) Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                            Row(Modifier.fillMaxSize().graphicsLayer { alpha = controlsAlpha }
                                .then(if (discovering) Modifier.clearAndSetSemantics { } else Modifier),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ControlManagementButton(R.string.router_review_router_vpn, R.drawable.lin_router,
                                    enabled && discoveryReady && !state.busy, Modifier.weight(1f).fillMaxHeight()) { page = ControlPage.ROUTER_VPN }
                                ControlManagementButton(R.string.router_review_vpn_clients, R.drawable.lin_profiles,
                                    enabled && discoveryReady && !state.busy, Modifier.weight(1f).fillMaxHeight()) {
                                    page = ControlPage.CLIENTS
                                    if (canAct) networkAction { coordinator.refreshClientStatus() }
                                }
                            }
                            androidx.compose.animation.AnimatedVisibility(discovering,
                                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(100)),
                                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))) {
                                ControlLoading(R.string.router_loader_discovering)
                            }
                        }
                        if (!discovering) ControlHubFooter(state, enabled, onPair, ::retryDiscovery)
                    }
                    ControlPage.ROUTER_VPN -> {
                        val activeId = state.snapshot?.desiredServerId
                        val selectedId = state.selectedServerId
                        val switchProfile = !state.updates.running && !state.updates.requiresVpnStop && activeId != null && selectedId != null && selectedId != activeId
                        if (selectedId != null || activeId != null) {
                            Button(onClick = { networkAction { coordinator.setVpn(state.vpnButtonStarts) } },
                                enabled = enabled && (state.controlsAvailable || state.canStopVpn),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                ControlActionLabel(when {
                                    switchProfile -> R.string.router_review_switch_profile
                                    activeId != null -> R.string.router_review_disconnect
                                    else -> R.string.router_review_start_selected
                                }, R.drawable.lin_power)
                            }
                        }
                        if (rootPage) ControlHubFooter(state, enabled, onPair) { networkAction { coordinator.refreshConnectedRouter() } }
                    }
                    ControlPage.CLIENTS -> if (state.hasConfirmedDirectIp) Button(
                        onClick = { clientName = ""; page = ControlPage.CREATE }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = canAct && !state.clientCreationNeedsSetup,
                    ) { ControlActionLabel(R.string.router_review_create_profile, R.drawable.lin_plus) }
                    ControlPage.SETTINGS -> Button(onClick = {
                        page = ControlPage.UPDATES
                        networkAction {
                            coordinator.refreshUpdates()
                            val latest = coordinator.state.value.updates
                            if (latest.canCheck && latest.status?.availability != RouterUpdateAvailability.AVAILABLE) coordinator.checkUpdates()
                        }
                    }, enabled = canAct && !state.updates.running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        ControlActionLabel(if (!state.updates.deviceCheckedCurrent && !state.updates.running &&
                            state.updates.status?.availability == RouterUpdateAvailability.AVAILABLE)
                            R.string.router_updates_available else R.string.router_updates_check_entry, R.drawable.lin_refresh)
                    }
                    ControlPage.DEVICE -> Unit
                    ControlPage.UPDATES -> VpnRouterPackageUpdateFooter(state.updates,
                        enabled && !discoveryPending && !state.busy && state.preferences?.enabled == true && state.stage != RouterControlStage.PAIRING_REQUIRED,
                        onCheck = { networkAction { coordinator.checkUpdates() } },
                        onApply = { state.updates.plan?.let { plan -> networkAction { coordinator.applyUpdate(plan.planId, plan.planSha256) } } },
                        onRefresh = { networkAction { coordinator.refreshUpdates() } })
                    ControlPage.PROTOCOLS -> if (engineSelection.isNotEmpty() && !state.engines.running) Button(
                        onClick = { networkAction {
                            coordinator.planEngines(RouterEngineOperation.ADD, engineSelection)
                            if (coordinator.state.value.engines.plan != null) page = ControlPage.ENGINE_PLAN
                        } }, enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.vpn_continue)) }
                    else OutlinedButton(onClick = { networkAction { coordinator.refreshEngines() } }, enabled = canAct,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.router_engines_check_status)) }
                    ControlPage.ENGINE_PLAN -> {
                        val plan = state.engines.plan
                        val apply: () -> Unit = { if (plan != null) networkAction {
                            coordinator.applyEnginePlan(plan.planId, plan.planSha256)
                            if (coordinator.state.value.engines.job?.planId == plan.planId && coordinator.state.value.engines.failure == null && coordinator.state.value.error == null) {
                                engineSelection = emptySet()
                                page = ControlPage.ENGINE_JOB
                            }
                        } }
                        if (plan?.operation == RouterEngineOperation.REMOVE) OutlinedButton(onClick = apply,
                            enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            border = BorderStroke(1.dp, vpnDeleteColor()), colors = ButtonDefaults.outlinedButtonColors(contentColor = vpnDeleteColor())) {
                            ControlActionLabel(R.string.vpn_remove, R.drawable.lin_trash)
                        } else Button(onClick = apply, enabled = canAct && plan != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if (plan?.operation == RouterEngineOperation.ADD) R.string.vpn_router_install
                                else if (plan?.impact == RouterEngineImpact.RECONNECT_REQUIRED) R.string.router_engines_update_reconnect else R.string.router_engines_update))
                        }
                    }
                    ControlPage.ENGINE_JOB -> OutlinedButton(onClick = { networkAction {
                        val job = coordinator.state.value.engines.job
                        if (job?.running == true) coordinator.refreshEngineJob(job.jobId) else coordinator.refreshEngines()
                    } }, enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.router_engines_check_status))
                    }
                    ControlPage.UNPAIR -> ControlConfirmationFooter(enabled, onCancel = ::dashboard,
                        confirmLabel = R.string.router_review_confirm_unpair,
                        onConfirm = { unpairId?.let { id ->
                            if (deletePackage) networkAction { coordinator.unpair(id, deletePackage = true) }
                            else scope.launch {
                                coordinator.unpair(id)
                                if (current && coordinator.state.value.savedRouters.none { it.id == id }) dashboard()
                            }
                        } }, confirmEnabled = unpairId != null && state.savedRouters.any { it.id == unpairId } &&
                            (!deletePackage || unpairId == state.preferences?.pairingId && state.packageRemovalAvailable))
                    ControlPage.REMOVE -> ControlConfirmationFooter(canAct, onCancel = ::back, confirmLabel = R.string.vpn_remove,
                        onConfirm = { target?.let { selected -> removalRevision?.let { revision -> networkAction {
                            if (selected.remote) coordinator.removeRemoteClient(selected.id, revision)
                            else coordinator.removeServer(selected.id, revision)
                            if (coordinator.state.value.error == null) {
                                qrProfile = null
                                page = if (selected.remote) ControlPage.CLIENTS else ControlPage.ROUTER_VPN
                            }
                        } } } }, confirmEnabled = item != null && removalRevision == state.snapshot?.revision)
                    ControlPage.CREATE -> ControlCreateFooter(canAct && state.hasConfirmedDirectIp, clientName,
                        onCancel = ::back, onCreate = { networkAction {
                            coordinator.createRemoteClient(clientName)
                            if (coordinator.state.value.error == null) {
                                page = ControlPage.CLIENTS
                                coordinator.refreshClientStatus()
                            }
                        } })
                    ControlPage.ACTIONS, ControlPage.PROFILE -> VpnRouterProfileActionsFooter(canAct && item != null,
                        onShare = { target?.let { selected -> networkAction {
                            if (selected.remote) coordinator.showRemoteClient(selected.id) { shareVpnProfile(context, it) }
                            else coordinator.showServer(selected.id) { shareVpnProfile(context, it) }
                        } } }, onRemove = { removalRevision = state.snapshot?.revision; page = ControlPage.REMOVE })
                }
                if (page !in setOf(ControlPage.UNPAIR, ControlPage.REMOVE, ControlPage.CREATE))
                    VpnRouterNavigationFooter(enabled && !discoveryPending && !state.busy, if (rootPage) null else ::back, onClose)
            }
        },
    ) {
        AnimatedContent(page, modifier = Modifier.fillMaxWidth(), transitionSpec = {
            if (initialState == ControlPage.LOADING || targetState == ControlPage.LOADING)
                androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None, androidx.compose.animation.ExitTransition.None, sizeTransform = null)
            else VpnSheetMotion.pageTransition(animateSize = true)
        }, label = "router-control-navigation") { shownPage ->
            when (shownPage) {
                ControlPage.LOADING, ControlPage.HUB -> ControlHub(state, enabled, ::selectRouter, ::renameRouter, ::routerSettings, ::removeRouter, discovering)
                ControlPage.ROUTER_VPN -> ControlRouterVpn(if (enabled && shownPage == page) state else state.copy(busy = true),
                    profilesListState, profilesExpanded, devicesExpanded,
                    onDevicesExpanded = { value ->
                        devicesExpanded = value
                        if (value) {
                            if (canAct) requestDevices() else { devicesPending = false; devicesFailed = true }
                        }
                    },
                    devicesPending = devicesPending, devicesFailed = devicesFailed,
                    onRetryDevices = {
                        if (canAct) requestDevices()
                        else { devicesExpanded = false; dashboard(); retryDiscovery() }
                    },
                    onDevice = { id -> target = RouterActionTarget(id, device = true); page = ControlPage.DEVICE },
                    onDeviceRoute = { id, vpn -> networkAction { coordinator.setLanClient(id, vpn) } },
                    onProfilesExpanded = { profilesExpanded = it }, showRouterSelector = landing != ControlPage.HUB,
                    onRouterSelect = ::selectRouter,
                    onSelect = coordinator::selectServer,
                    onActions = { id -> target = RouterActionTarget(id); page = ControlPage.ACTIONS })
                ControlPage.PROTOCOLS -> VpnRouterProtocolsList(state.engines, engineSelection, enginesListState,
                    canAct && shownPage == page, onSelect = { engineSelection = it },
                    onUpdate = { protocol -> networkAction {
                        coordinator.planEngines(RouterEngineOperation.UPDATE, setOf(protocol))
                        if (coordinator.state.value.engines.plan != null) page = ControlPage.ENGINE_PLAN
                    } }, onRemove = { protocol -> networkAction {
                        coordinator.planEngines(RouterEngineOperation.REMOVE, setOf(protocol))
                        if (coordinator.state.value.engines.plan != null) page = ControlPage.ENGINE_PLAN
                    } }, onShowJob = { page = ControlPage.ENGINE_JOB })
                ControlPage.CLIENTS -> ControlClients(state, canAct && shownPage == page, clientsListState,
                    onViewClient = ::openClient, onClientActions = ::openClient)
                else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (shownPage) {
                        ControlPage.UNPAIR -> {
                            state.savedRouters.singleOrNull { it.id == unpairId }?.let { router ->
                                Text(router.label ?: stringResource(R.string.router_table_default_name, router.number),
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Text(stringResource(R.string.router_review_unpair_description))
                            if (unpairId == state.selectedRouterId) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(deletePackage, onCheckedChange = { selected ->
                                    if (!selected || coordinator.state.value.packageRemovalAvailable) deletePackage = selected
                                }, enabled = enabled && !discoveryPending && !state.busy && (deletePackage || state.packageRemovalAvailable))
                                Text(stringResource(R.string.router_review_delete_package), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!state.packageRemovalAvailable) Text(stringResource(R.string.router_review_delete_unavailable), style = MaterialTheme.typography.bodySmall, color = LocalVpnColors.current.muted)
                            }
                        }
                        ControlPage.SETTINGS -> ControlRouterSettings(
                            if (enabled && shownPage == page) state else state.copy(busy = true), coordinator.homeAutomationAvailable,
                            onEnabled = { value -> if (value) networkAction { coordinator.setEnabled(true) } else scope.launch { coordinator.setEnabled(false) } },
                            onCountry = { code, direct -> networkAction { coordinator.setCountry(code, direct) } },
                            onCountryDirect = { direct -> networkAction { coordinator.setCountryDirect(direct) } },
                            onCountryAuto = { networkAction { coordinator.setCountryAuto(true) } },
                            onHome = { value -> if (value) requestHomeEnable {
                                if (current && scope.isActive) scope.launch { coordinator.setHomeAutodetection(true) }
                            } else scope.launch { coordinator.setHomeAutodetection(false) } },
                            onPublicIp = { scope.launch { coordinator.setPublicIpConfirmed(it) } },
                            onHomeVpnManagement = { scope.launch { coordinator.setHomeVpnManagement(it) } },
                            onHideOffline = { hidden -> scope.launch { coordinator.setHideOfflineDevices(hidden) } },
                            onRouterVpn = { page = ControlPage.ROUTER_VPN },
                            onClients = {
                                page = ControlPage.CLIENTS
                                if (canAct) networkAction { coordinator.refreshClientStatus() }
                            },
                            onEngines = {
                                engineSelection = emptySet(); page = ControlPage.PROTOCOLS
                                networkAction { coordinator.refreshEngines() }
                            },
                        )
                        ControlPage.DEVICE -> {
                            Text(device?.name?.takeIf { it.isNotBlank() } ?: device?.address.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.router_review_current_route), style = MaterialTheme.typography.bodyMedium)
                            VpnRouterDeviceRouteDropdown(device?.route, canAct && device != null) { vpn ->
                                target?.let { selected -> networkAction { coordinator.setLanClient(selected.id, vpn) } }
                            }
                            state.snapshot?.servers?.singleOrNull { it.text("id") == state.snapshot?.desiredServerId }?.text("name")?.let { name ->
                                Text(stringResource(R.string.router_review_active_profile, name), style = MaterialTheme.typography.bodySmall, color = LocalVpnColors.current.muted)
                            }
                        }
                        ControlPage.UPDATES -> VpnRouterPackageUpdates(state.updates, enabled && !discoveryPending && !state.busy &&
                            state.stage == RouterControlStage.CONNECTED && shownPage == page,
                            onAuto = { value -> networkAction { coordinator.setUpdateAuto(value) } })
                        ControlPage.ENGINE_PLAN -> VpnRouterProtocolPlan(state.engines.plan)
                        ControlPage.ENGINE_JOB -> VpnRouterProtocolJob(state.engines.job)
                        ControlPage.CREATE -> {
                            Text(stringResource(R.string.router_control_create_help))
                            OutlinedTextField(clientName, onValueChange = { if (shownPage == page && it.toByteArray(Charsets.UTF_8).size <= 48) clientName = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canAct && shownPage == page,
                                label = { Text(stringResource(R.string.router_review_profile_name)) })
                        }
                        ControlPage.REMOVE -> Text(stringResource(
                            if (target?.remote != true) R.string.router_control_remove_body
                            else if (item?.get("provisioned") == JsonPrimitive(true)) R.string.router_control_client_remove_body
                            else R.string.router_control_client_remove_record_body, item?.text("name").orEmpty()))
                        ControlPage.PROFILE -> VpnRouterProfileManagementContent(item?.text("name").orEmpty(), "Hysteria2", qrProfile,
                            state.busy, item?.get("provisioned") == JsonPrimitive(true), canAct && item != null && shownPage == page,
                            onImport = { target?.let { selected -> networkAction {
                                coordinator.importRemoteClient(selected.id)
                                if (coordinator.state.value.error == null) notices.show(resources.getString(R.string.router_control_imported))
                            } } })
                        ControlPage.ACTIONS -> ControlClientDetails(item)
                        ControlPage.LOADING, ControlPage.HUB, ControlPage.ROUTER_VPN, ControlPage.CLIENTS, ControlPage.PROTOCOLS -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlHubFooter(state: RouterControlState, enabled: Boolean, onPair: () -> Unit, onRefresh: () -> Unit) {
    if (state.stage == RouterControlStage.PAIRING_REQUIRED) return
    if (state.stage == RouterControlStage.UNPAIRED && state.entryAccess?.canDiscover == true) {
        OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && !state.busy) {
            Text(stringResource(R.string.vpn_router_pair))
        }
    } else if (state.stage != RouterControlStage.CONNECTED) {
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && !state.busy) {
            Text(stringResource(R.string.vpn_refresh))
        }
    }
}

@Composable
private fun ControlConfirmationFooter(enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit,
    confirmEnabled: Boolean = true, confirmLabel: Int = R.string.vpn_continue, showCancel: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCancel) OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) {
            Text(stringResource(R.string.vpn_cancel))
        }
        OutlinedButton(onClick = onConfirm, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && confirmEnabled,
            border = BorderStroke(1.dp, vpnDeleteColor()), colors = ButtonDefaults.outlinedButtonColors(contentColor = vpnDeleteColor())) {
            ControlActionLabel(confirmLabel, R.drawable.lin_trash)
        }
    }
}

@Composable
private fun ControlCreateFooter(enabled: Boolean, name: String, onCancel: () -> Unit, onCreate: () -> Unit, showCancel: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCancel) OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) {
            Text(stringResource(R.string.vpn_cancel))
        }
        Button(onClick = onCreate, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = enabled && name.trim().isNotEmpty() && name.none(Char::isISOControl)) {
            ControlActionLabel(R.string.router_control_create, R.drawable.lin_plus)
        }
    }
}

@Composable
private fun ControlActionLabel(label: Int, icon: Int) {
    Icon(painterResource(icon), null, Modifier.size(18.dp),
        tint = if (icon == R.drawable.lin_trash) vpnDeleteColor() else androidx.compose.material3.LocalContentColor.current)
    Text(stringResource(label), Modifier.padding(start = 8.dp))
}

@Composable
private fun ControlClientDetails(item: JsonObject?) {
    Text(item?.text("name").orEmpty(), style = MaterialTheme.typography.titleMedium)
    Text(item?.text("endpoint").orEmpty(), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ControlLoading(label: Int) {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Text(stringResource(label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ControlHub(state: RouterControlState, enabled: Boolean, onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit, onSettings: (String) -> Unit, onDelete: (String) -> Unit, discovering: Boolean) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VpnRouterIllustration(Modifier.fillMaxWidth().height(168.dp).testTag("router-management-vector"), animateEntrance = false)
        Column(Modifier.fillMaxWidth().height(if (state.savedRouters.size > 1) 24.dp else 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            state.lastAuthenticatedPackageVersion?.let { version ->
                Text(stringResource(R.string.router_loader_paired_version, version),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            if (state.savedRouters.size < 2) IconButton(onClick = { state.selectedRouterId?.let(onSettings) },
                enabled = enabled && !discovering && !state.busy &&
                    state.savedRouters.any { it.id == state.selectedRouterId && it.status == com.foxhole.vpn.router.RouterSavedStatus.AVAILABLE },
                modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.router_review_settings), Modifier.size(22.dp))
            }
        }
        if (state.savedRouters.size > 1) VpnRouterSavedTable(state.savedRouters, state.selectedRouterId,
            enabled && !discovering && !state.busy,
            onSelect = onSelect, onRename = onRename, onSettings = onSettings,
            deleteEnabled = enabled, onDelete = onDelete)
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
            if (!discovering) {
                if (state.stage == RouterControlStage.CONNECTED && state.snapshot != null) ControlInstalledProtocols(state)
                else Text(stringResource(when {
                    state.entryAccess == com.foxhole.vpn.router.RouterManagementAccess.VPN_ACTIVE || state.error == RouterWizardError.VPN_ACTIVE -> R.string.router_management_disconnect_vpn
                    state.stage == RouterControlStage.DISABLED -> R.string.router_review_control_disabled
                    state.stage == RouterControlStage.PAIRING_REQUIRED -> R.string.router_control_pairing_required
                    else -> R.string.router_management_unavailable_home
                }),
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ControlInstalledProtocols(state: RouterControlState) {
    val protocols = routerInstalledProtocols(state.snapshot?.installedProtocols, state.engines.status?.entries)
    val text = when {
        protocols == null && state.snapshot == null && state.busy -> stringResource(R.string.router_control_protocols_loading)
        protocols == null -> stringResource(R.string.router_control_protocols_unknown)
        protocols.isEmpty() -> stringResource(R.string.router_control_protocols_empty)
        else -> stringResource(R.string.router_control_available_protocols, protocols.joinToString(", ") { it.label })
    }
    Text(text, Modifier.fillMaxWidth().heightIn(min = 40.dp), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall, color = routerConnectColor())
}

@Composable
private fun ControlManagementButton(title: Int, icon: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(title), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlRouterVpn(state: RouterControlState, listState: LazyListState,
    profilesExpanded: Boolean, devicesExpanded: Boolean, onDevicesExpanded: (Boolean) -> Unit,
    devicesPending: Boolean, devicesFailed: Boolean, onRetryDevices: () -> Unit,
    onDevice: (String) -> Unit, onDeviceRoute: (String, Boolean) -> Unit,
    onProfilesExpanded: (Boolean) -> Unit, showRouterSelector: Boolean,
    onRouterSelect: (String) -> Unit, onSelect: (String) -> Unit, onActions: (String) -> Unit) {
    val snapshot = state.snapshot
    val devices = visibleRouterLanDevices(state.lanDevices, state.preferences?.hideOfflineDevices != false)
    val devicesReady = !devicesPending && !devicesFailed && !state.lanDevicesLoading && state.lanDevicesLoaded
    val deviceRouteWidth = minOf(152.dp, LocalConfiguration.current.screenWidthDp.dp * 0.38f)
    LazyColumn(Modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (showRouterSelector) item {
            if (state.savedRouters.size > 1) VpnRouterSavedSelector(state.savedRouters, state.selectedRouterId, !state.busy,
                showIllustration = true, onSelect = onRouterSelect)
            else VpnRouterIllustration(Modifier.fillMaxWidth().height(128.dp))
        }
        if (showRouterSelector) item { ControlInstalledProtocols(state) }
        item {
            if (!state.controlsAvailable && !state.busy) Text(stringResource(
                if (state.stage == RouterControlStage.DISABLED) R.string.router_review_control_disabled else R.string.router_review_unavailable))
            val activeName = snapshot?.servers?.singleOrNull { it.text("id") == snapshot?.desiredServerId }?.text("name")
            if (activeName != null) Text(stringResource(R.string.router_review_active_profile, activeName),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = routerConnectColor())
            ControlExpandableSection(R.string.router_review_profiles, R.drawable.lin_profiles, R.string.router_control_vpn_help,
                profilesExpanded, R.string.router_review_expand_profiles, R.string.router_review_collapse_profiles) {
                onProfilesExpanded(!profilesExpanded)
            }
        }
        if (profilesExpanded && snapshot?.servers.isNullOrEmpty()) item {
            Text(stringResource(R.string.router_review_no_profiles), style = MaterialTheme.typography.bodySmall)
        }
        if (profilesExpanded) items(snapshot?.servers.orEmpty(), key = { "server:" + it.text("id") }) { server ->
            val id = server.text("id").orEmpty()
            VpnRouterTableRow(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = VpnSheetMotion.defaultSpatialSpec())
                .fillMaxWidth().heightIn(min = 52.dp).combinedClickable(enabled = state.controlsAvailable,
                    onClick = { onSelect(id) }, onLongClick = { onActions(id) })) {
                Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.text("name").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(server.text("protocol").orEmpty().uppercase(java.util.Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall, color = LocalVpnColors.current.muted)
                }
                RadioButton(state.selectedServerId == id, onClick = null, enabled = state.controlsAvailable)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            ControlExpandableSection(R.string.router_control_lan_clients, R.drawable.lin_router, R.string.router_control_lan_help,
                devicesExpanded, R.string.router_review_expand_devices, R.string.router_review_collapse_devices) {
                onDevicesExpanded(!devicesExpanded)
            }
        }
        if (devicesExpanded && (devicesPending || state.lanDevicesLoading)) item(key = "devices-loading") {
            ControlLoading(R.string.router_loader_data)
        } else if (devicesExpanded && !devicesReady) item(key = "devices-failed") {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.router_review_unavailable), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetryDevices, enabled = !state.busy && !state.updates.running && !state.engines.running) {
                    Text(stringResource(R.string.vpn_refresh))
                }
            }
        } else if (devicesExpanded && devices.isEmpty()) item {
            Text(stringResource(if (state.preferences?.hideOfflineDevices != false) R.string.router_review_no_online_devices
                else R.string.router_review_no_devices), style = MaterialTheme.typography.bodySmall)
        }
        if (devicesExpanded && devicesReady) items(devices, key = { "device:" + it.id }) { device ->
            VpnRouterTableRow(Modifier.animateItem(fadeInSpec = VpnSheetMotion.fastEffectsSpec(), fadeOutSpec = VpnSheetMotion.fastEffectsSpec(), placementSpec = VpnSheetMotion.defaultSpatialSpec())
                .fillMaxWidth().heightIn(min = 52.dp).combinedClickable(enabled = state.controlsAvailable,
                    onClick = { onDevice(device.id) }, onLongClick = { onDevice(device.id) })) {
                Icon(painterResource(when (routerLanDeviceKind(device.name)) {
                    RouterLanDeviceKind.PHONE -> R.drawable.router_device_phone
                    RouterLanDeviceKind.TABLET -> R.drawable.router_device_tablet
                    RouterLanDeviceKind.COMPUTER -> R.drawable.router_device_computer
                    RouterLanDeviceKind.UNKNOWN -> R.drawable.router_device_generic
                }), null, Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name.takeIf { it.isNotBlank() } ?: device.address, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Box(Modifier.widthIn(max = deviceRouteWidth)) {
                    VpnRouterDeviceRouteDropdown(device.route, state.controlsAvailable) { vpn ->
                        onDeviceRoute(device.id, vpn)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }


    }
}

@Composable
private fun ControlRouterSettings(state: RouterControlState, supportsHome: Boolean,
    onEnabled: (Boolean) -> Unit, onCountry: (String, Boolean) -> Unit,
    onCountryDirect: (Boolean) -> Unit, onCountryAuto: () -> Unit, onHome: (Boolean) -> Unit,
    onPublicIp: (Boolean) -> Unit, onHomeVpnManagement: (Boolean) -> Unit,
    onHideOffline: (Boolean) -> Unit, onRouterVpn: () -> Unit, onClients: () -> Unit, onEngines: () -> Unit) {
    val preferences = state.preferences
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ControlSwitch(R.string.router_control_enable, R.string.router_control_enable_help,
            state.managementEnabled, state.canToggleManagement, onEnabled)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val automatic = preferences?.countryAuto != false
        val effectiveCountry = knownRouterHomeCountry(if (automatic) state.routerCountryCode else preferences?.countryCode).orEmpty()
        // Cached country is display-only while automatic discovery is pending.
        val displayCountry = routerDisplayCountryCode(preferences, state.routerCountryCode, state.snapshot?.countries).orEmpty()
        VpnRouterTableRow(Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(20.dp))
            Text(stringResource(R.string.router_review_local_traffic), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            VpnRouterCountryFlag(displayCountry, state.controlsAvailable,
                onSelect = { onCountry(it, preferences?.countrySitesDirect != false) }, autoSelected = automatic, onAuto = onCountryAuto)
            VpnRouterHelpButton(R.string.router_review_local_traffic, R.string.router_help_country_source)
            VpnSwitch(state.countryDirectEffective ?: (preferences?.countrySitesDirect != false),
                onCheckedChange = onCountryDirect, enabled = state.controlsAvailable && effectiveCountry.isNotEmpty())
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ControlSwitch(R.string.router_control_auto_home, R.string.router_control_home_help, state.homeAutomationEffective,
            !state.busy && preferences != null && (state.homeAutomationEffective || state.controlsAvailable && supportsHome), onHome)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ControlSwitch(R.string.router_review_direct_ip, R.string.router_review_remote_help,
            preferences?.publicIpConfirmed == true, !state.busy && preferences != null, onPublicIp)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ControlSwitch(R.string.router_control_home_vpn, R.string.router_control_home_vpn_help,
            preferences?.homeVpnManagementEnabled == true,
            !state.busy && preferences?.publicIpConfirmed == true,
            onHomeVpnManagement)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ControlSwitch(R.string.router_control_hide_offline, R.string.router_control_hide_offline_help,
            preferences?.hideOfflineDevices != false, !state.busy && preferences != null, onHideOffline)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.engines.supported == true) ControlNavigationRow(R.string.router_engines_entry,
            R.drawable.lin_power, state.controlsAvailable, onEngines)
    }
}

@Composable
private fun ControlNavigationRow(label: Int, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    VpnRouterTableRow(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(painterResource(R.drawable.lin_chevron_right), null, Modifier.size(20.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlClients(state: RouterControlState, enabled: Boolean, listState: LazyListState,
    onViewClient: (String) -> Unit, onClientActions: (String) -> Unit) {
    val clients = state.snapshot?.clients.orEmpty()
    val stackedStatus = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale > 1.3f
    LazyColumn(Modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            if (!state.controlsAvailable && !state.busy) Text(stringResource(
                if (state.stage == RouterControlStage.DISABLED) R.string.router_review_control_disabled else R.string.router_review_unavailable))
            if (state.clientCreationNeedsSetup) Text(stringResource(R.string.router_control_client_template), style = MaterialTheme.typography.bodySmall)
        }
        if (clients.isEmpty()) item { Text(stringResource(R.string.router_review_no_clients), style = MaterialTheme.typography.bodySmall) }
        items(clients, key = { "remote:" + it.text("id") }) { client ->
            val id = client.text("id").orEmpty()
            val online = state.remoteClients.singleOrNull { it.id == id }?.online
            VpnRouterTableRow(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = VpnSheetMotion.defaultSpatialSpec())
                .fillMaxWidth().heightIn(min = 52.dp).combinedClickable(enabled = enabled,
                    onClick = { onViewClient(id) }, onLongClick = { onClientActions(id) })) {
                Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
                val status = stringResource(when (online) {
                    true -> R.string.router_review_online
                    false -> R.string.router_review_offline
                    null -> R.string.router_review_status_unknown
                })
                Column(Modifier.weight(1f)) {
                    Text(client.text("name").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (stackedStatus) Text(status, style = MaterialTheme.typography.labelSmall, color = LocalVpnColors.current.muted)
                }
                if (!stackedStatus) Text(status, Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelSmall,
                    color = LocalVpnColors.current.muted, textAlign = TextAlign.End)
                Icon(painterResource(R.drawable.lin_chevron_right), null, Modifier.size(20.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ControlExpandableSection(label: Int, icon: Int, help: Int, expanded: Boolean,
    expandDescription: Int, collapseDescription: Int, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, VpnSheetMotion.defaultSpatialSpec(), label = "router-section-chevron")
    VpnRouterTableRow(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        VpnHelpLabel(stringResource(label), label, help, Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onToggle) {
            Icon(painterResource(R.drawable.lin_chevron_down), stringResource(if (expanded) collapseDescription else expandDescription), Modifier.rotate(angle))
        }
    }
}

@Composable
private fun ControlSwitch(label: Int, help: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    VpnRouterTableRow(Modifier.fillMaxWidth()) {
        val icon = when (label) {
            R.string.router_control_vpn -> R.drawable.lin_power
            R.string.router_control_enable -> R.drawable.lin_settings
            R.string.router_control_auto_home, R.string.router_control_home_vpn -> R.drawable.lin_router
            R.string.router_review_direct_ip -> R.drawable.router_review_globe
            R.string.router_control_hide_offline -> R.drawable.lin_eye_off
            else -> null
        }
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
        VpnHelpLabel(stringResource(label), label, help, Modifier.weight(1f))
        VpnSwitch(checked, onCheckedChange = onChange, enabled = enabled)
    }
    HorizontalDivider(color = LocalVpnColors.current.border.copy(alpha = .5f))
}

private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
