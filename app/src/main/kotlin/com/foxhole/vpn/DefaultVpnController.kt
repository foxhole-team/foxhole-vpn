package com.foxhole.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.foxhole.core.runtime.VpnEngineConfig
import com.foxhole.core.runtime.FoxholeNativeEngine
import com.foxhole.vpn.updates.VpnDataUpdater
import com.foxhole.vpn.updates.VpnDataUpdatePhase
import java.io.File
import java.net.Socket
import com.foxhole.core.runtime.FoxCoreConfigTranslationException
import com.foxhole.vpn.engine.AndroidEngineDriver
import com.foxhole.vpn.engine.EngineDriver
import com.foxhole.vpn.engine.EngineFailure
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSnapshot
import com.foxhole.vpn.engine.VpnRuntimeOwner
import com.foxhole.vpn.router.RouterSocketOwnerRegistry
import com.foxhole.vpn.router.RouterSocketOwnerSnapshot
import com.foxhole.vpn.profile.ImportedVpnProfiles
import com.foxhole.vpn.profile.InsecureTlsConsentRequired
import com.foxhole.vpn.profile.SubscriptionFetcher
import com.foxhole.vpn.profile.VpnProfile
import com.foxhole.vpn.profile.VpnProfileImporter
import com.foxhole.vpn.profile.exportVpnProfile
import com.foxhole.vpn.profile.exportWholeVpnProfile
import com.foxhole.vpn.profile.withSelectedProtocol
import com.foxhole.vpn.runtime.VpnTunnelService
import com.foxhole.vpn.router.RouterAutomaticPause
import com.foxhole.vpn.router.RouterHomePhoneSnapshot
import com.foxhole.vpn.router.RouterPausedPhone
import com.foxhole.vpn.router.RouterPhoneSession
import com.foxhole.vpn.router.RouterBoundDialPlan
import com.foxhole.vpn.router.RouterDialCandidate
import com.foxhole.vpn.router.prepareResolvedRouterDialCandidate
import com.foxhole.vpn.router.routerDialPlanCurrent
import com.foxhole.core.runtime.PreparedVpnConfig
import com.foxhole.vpn.router.routerConnectedPhone
import com.foxhole.vpn.router.routerResumeOwned
import com.foxhole.vpn.router.routerAutomaticProfilesProtected
import com.foxhole.vpn.router.routerAutomaticLeaseProtected
import com.foxhole.vpn.router.routerAutomaticPreparedLeaseProtected
import com.foxhole.vpn.refresh.ForegroundIpRefresh
import com.foxhole.vpn.refresh.RefreshAttempt
import com.foxhole.vpn.refresh.RefreshOutcome
import com.foxhole.vpn.refresh.SubscriptionRefreshRun
import com.foxhole.vpn.refresh.SubscriptionRefreshTarget
import com.foxhole.vpn.refresh.VpnRefreshWork
import com.foxhole.vpn.refresh.attempted
import com.foxhole.vpn.refresh.proposeSubscriptionRefresh
import com.foxhole.vpn.refresh.refreshIntervalMillis
import com.foxhole.vpn.refresh.refreshIsDue
import com.foxhole.vpn.refresh.subscriptionRefreshTargets
import com.foxhole.vpn.refresh.subscriptionSourceKey
import com.foxhole.vpn.storage.EncryptedProfileStore
import com.foxhole.vpn.storage.DevicePlaceCacheWriter
import com.foxhole.vpn.storage.StoredDevicePlace
import com.foxhole.vpn.storage.ProfileStoreState
import com.foxhole.vpn.storage.storedDevicePlace
import com.foxhole.vpn.widget.WidgetHomeRefreshWork
import com.foxhole.vpn.widget.statusWidgetsPresent
import com.foxhole.vpn.widget.widgetHomeRefreshAllowed
import com.foxhole.vpn.widget.refreshWidgetHomeLocation as refreshWidgetHome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

internal class DefaultVpnController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val licenseAccepted: () -> Boolean,
) : VpnController {
    private val mutable = MutableStateFlow(VpnUiState())
    override val state: StateFlow<VpnUiState> = mutable
    private val settingsMutable = MutableStateFlow(VpnSettings())
    override val settings: StateFlow<VpnSettings> = settingsMutable
    private val ready = CompletableDeferred<Unit>()
    private val storage = EncryptedProfileStore(context)
    private val importer = VpnProfileImporter()
    private val fetcher by lazy { SubscriptionFetcher() }
    private val mutations = Mutex()
    private val widgetLocationOperations = Mutex()
    private val commands = ConnectionCommandFence()
    private val routerManualMutable = MutableStateFlow(0L)
    internal val routerManualGeneration: StateFlow<Long> = routerManualMutable
    private var routerPausedPhone: RouterPausedPhone? = null
    @Volatile internal var routerLeaseOwner: (() -> RouterPhoneSession?)? = null
    private var routerPreparedStart: Pair<Long, RouterBoundDialPlan>? = null
    private var routerLaunchingLease: Pair<Long, RouterBoundDialPlan>? = null
    @Volatile internal var routerStartPreparation: (suspend (RouterPhoneSession) -> RouterBoundDialPlan?)? = null
    private val connectionCues = ConnectionCueClaims()
    private val drivers = ConcurrentHashMap<VpnTunnelService, AndroidEngineDriver>()
    private val runtime = VpnRuntimeOwner(scope, ::finishService, ::terminatePoisonedProcess)
    private val routerSocketOwners = RouterSocketOwnerRegistry(commands, ::captureRouterSocketOwner) { owner, socket ->
        owner.service.protect(socket)
    }
    private val devicePlaceCache = DevicePlaceCacheWriter(scope, { ready.await() }, ::saveCachedDevicePlace)
    private val deviceLocation = DeviceNetworkLocationTracker(context, scope, devicePlaceCache::confirmedHome)
    private val visibleActivities = mutableSetOf<MainActivity>()
    internal val dataUpdater = VpnDataUpdater(
        File(context.noBackupFilesDir, "connection-data"), BuildConfig.VERSION_NAME,
        { document ->
            try { FoxholeNativeEngine.nativeInstallTlsFingerprintTables(document) }
            catch (_: LinkageError) { FoxholeNativeEngine.TLS_FINGERPRINT_TABLES_UNREADABLE }
        },
        { try { FoxholeNativeEngine.nativeClearTlsFingerprintTables() } catch (_: LinkageError) { } },
    )
    private var dataCheckJob: Job? = null
    private var dataUpdateJob: Job? = null
    private var dataCheckEpoch = 0
    private val dataOperations = Mutex()
    private val subscriptionOperations = Mutex()
    private val refreshWork = VpnRefreshWork(context)
    private val ipRefresh = ForegroundIpRefresh(scope, ::refreshForegroundIp)
    private var subscriptionCheckJob: Job? = null
    private val automaticSubscriptionMutable = MutableStateFlow(SubscriptionRefreshRun())
    internal val automaticSubscriptionState: StateFlow<SubscriptionRefreshRun> = automaticSubscriptionMutable
    @Volatile private var stored = ProfileStoreState()
    @Volatile private var storageFailed = false
    @Volatile private var profileStoreFault = false
    @Volatile private var locationStoreReady = false
    @Volatile private var locationForeground = false
    private var pendingUiFailure: PendingRuntimeUiFailure? = null
    // Service dispatch precedes the actor's first snapshot for this ticket.
    private var awaitingRuntimeTicket: Long? = null

    init {
        scope.launch {
            var previouslyActive = false
            mutable.map { it.canDisconnect || it.isConnected }.distinctUntilChanged()
                .collect { active ->
                    deviceLocation.setTunnelActive(active)
                    if (previouslyActive && !active) WidgetHomeRefreshWork.request(context)
                    previouslyActive = active
                }
        }
        scope.launch {
            deviceLocation.state.collect { location -> mutable.update { it.copy(deviceLocation = location) } }
        }
        scope.launch {
            var lastDiagnostic: String? = null
            runtime.state.collect { incoming ->
                val diagnostic = incoming.validationFailureCode
                if (diagnostic != null && diagnostic != lastDiagnostic) {
                    android.util.Log.w("FoxHoleVpn", "VPN validation failed: $diagnostic")
                }
                lastDiagnostic = diagnostic
                synchronized(commands) {
                    val snapshot = runtime.state.value
                    mutable.update { current -> if (storageFailed) current else projectVpnRuntime(
                        current, snapshot, commands.currentGeneration(), pendingRuntimeStart(snapshot), pendingUiFailure,
                        message(R.string.vpn_error_secure_storage).takeIf { profileStoreFault },
                        { message(errorResource(it)) }, ::message,
                    ) }
                }
            }
        }
        scope.launch {
            try {
                dataUpdater.restore()
                val value = storage.read()
                value.lastKnownDevicePlace?.restore()?.location?.let(deviceLocation::restoreCached)
                publish(value)
                synchronized(visibleActivities) {
                    locationStoreReady = true
                    deviceLocation.setForeground(visibleActivities.isNotEmpty())
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                storageFailed = true
                mutable.value = VpnUiState(isReady = true, phase = VpnPhase.ERROR, message = message(R.string.vpn_error_secure_storage))
            } finally { ready.complete(Unit) }
        }
    }

    override suspend fun awaitReady() { ready.await() }

    private suspend fun saveCachedDevicePlace(place: StoredDevicePlace) = mutations.withLock {
        if (storageFailed || profileStoreFault || stored.lastKnownDevicePlace == place) return@withLock
        stored = storage.update { it.copy(lastKnownDevicePlace = place) }
    }

    internal suspend fun refreshWidgetHomeLocation(): Boolean {
        awaitReady()
        return widgetLocationOperations.withLock {
            val generation = commands.currentGeneration()
            fun admitted(): Boolean {
                if (storageFailed || profileStoreFault || locationForeground || !statusWidgetsPresent(context)) return false
                return synchronized(commands) {
                    val current = state.value
                    val snapshot = runtime.state.value
                    commands.isCurrentWithoutPendingStart(generation) && !snapshot.hasOwnedTunnel &&
                        widgetHomeRefreshAllowed(current, hasWidgets = true, hasPendingStart = pendingRuntimeStart(snapshot))
                }
            }
            if (!admitted()) return@withLock false
            supervisorScope {
                val lookup = async {
                    val lookupContext = currentCoroutineContext()
                    refreshWidgetHome(::admitted, deviceLocation::widgetHomeNetwork, deviceLocation::lookupWidgetHome) { network, location ->
                        mutations.withLock cacheMutation@ {
                            val expectedLocation = deviceLocation.state.value
                            fun publicationCurrent(): Boolean = admitted() && deviceLocation.widgetHomeNetwork() == network &&
                                deviceLocation.state.value == expectedLocation
                            fun current(): Boolean = lookupContext.isActive && publicationCurrent()
                            val place = storedDevicePlace(location) ?: return@cacheMutation false
                            val before = stored.lastKnownDevicePlace
                            fun accept(): Boolean = synchronized(commands) {
                                if (!current()) false else {
                                    deviceLocation.confirmWidgetHome(location, expectedLocation, ::publicationCurrent)
                                    true
                                }
                            }
                            if (place == before) return@cacheMutation accept()
                            commitProfileMutation(before, place,
                                { transform ->
                                    storage.update { it.copy(lastKnownDevicePlace = transform(it.lastKnownDevicePlace)) }
                                        .also { stored = it }.lastKnownDevicePlace
                                }, ::current, publishIfCurrent = { accept() },
                            ) == ProfileMutationResult.APPLIED
                        }
                    }
                }
                val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    mutable.collect { if (!admitted()) lookup.cancel() }
                }
                try { lookup.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    false
                } finally { monitor.cancel() }
            }
        }
    }

    override fun connectSelected() = synchronized(commands) {
        if (!licenseAccepted()) {
            showMessage(R.string.vpn_license_required)
            return@synchronized
        }
        routerManualAction()
        val current = state.value
        val stopping = current.phase == VpnPhase.STOPPING && current.canRestartDuringStop && current.message == null &&
            !current.hasPendingStart && !commands.hasPendingStart()
        if (!current.isReady || storageFailed || profileStoreFault || current.isConnected || current.canDisconnect && !stopping) return@synchronized
        val selected = current.selectedProfileId
        if (selected == null) { showMessage(R.string.vpn_error_no_profile); return@synchronized }
        val ticket = commands.reserveStart()
        commands.applyCurrent(ticket) {
            pendingUiFailure = null
            awaitingRuntimeTicket = null
            deviceLocation.setTunnelActive(true)
            mutable.update { it.copy(phase = VpnPhase.CONNECTING, hasPendingStart = true, canRestartDuringStop = false, location = null, message = null) }
        }
        scope.launch {
            awaitReady()
            requestStart(selected, ticket)
        }
        Unit
    }

    private fun requestStart(id: String, ticket: Long) {
        if (!licenseAccepted()) {
            failPendingStart(ticket, R.string.vpn_license_required, stopExisting = false)
            return
        }
        val preparation = routerStartPreparation
        if (preparation == null) { requestStartNow(id, ticket); return }
        val owner = synchronized(commands) {
            if (!commands.isCurrent(ticket)) null else RouterPhoneSession(id, state.value.profileRevision, ticket)
        } ?: return
        scope.launch {
            try {
                val plan = preparation(owner)
                val current = synchronized(commands) {
                    if (!commands.isCurrent(ticket) || state.value.profileRevision != owner.profileRevision || stored.selectedProfileId != id) false
                    else { routerPreparedStart = plan?.let { ticket to it }; routerLaunchingLease = routerPreparedStart; true }
                }
                if (current) requestStartNow(id, ticket)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failPendingStart(ticket, R.string.vpn_error_connection_failed, stopExisting = false) }
        }
    }

    private fun requestStartNow(id: String, ticket: Long) {
        commands.applyCurrent(ticket) {
            if (!licenseAccepted()) {
                failPendingStart(ticket, R.string.vpn_license_required, stopExisting = false)
            } else if (profileStoreFault) {
                failPendingStart(ticket, R.string.vpn_error_secure_storage, stopExisting = false)
            } else if (VpnService.prepare(context) != null) {
                failPendingStart(ticket, R.string.vpn_error_permission_required)
            } else if (storageFailed || stored.selectedProfileId != id || stored.profiles.none { it.id == id }) {
                failPendingStart(ticket, R.string.vpn_error_profile_unavailable)
            } else {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, VpnTunnelService::class.java)
                        .setAction(VpnTunnelService.ACTION_CONNECT)
                        .putExtra(VpnTunnelService.EXTRA_PROFILE, id)
                        .putExtra(VpnTunnelService.EXTRA_COMMAND, ticket))
                } catch (_: RuntimeException) {
                    failPendingStart(ticket, R.string.vpn_error_connection_failed)
                }
            }
        }
    }

    private fun failPendingStart(ticket: Long, resource: Int, stopExisting: Boolean = true) {
        commands.applyCurrent(ticket) {
            commands.abandon(ticket)
            if (awaitingRuntimeTicket == ticket) awaitingRuntimeTicket = null
            pendingUiFailure = PendingRuntimeUiFailure(commands.currentGeneration(), resource)
            if (stopExisting) runtime.stop()
            val snapshot = runtime.state.value
            mutable.update { projectVpnRuntime(
                it, snapshot, commands.currentGeneration(), pendingRuntimeStart(snapshot), pendingUiFailure,
                message(R.string.vpn_error_secure_storage).takeIf { profileStoreFault },
                { failure -> message(errorResource(failure)) }, ::message,
            ) }
        }
    }

    override fun disconnect() = synchronized(commands) {
        routerManualAction()
        commands.stop { pendingUiFailure = null; awaitingRuntimeTicket = null; stopRuntime() }
    }

    private fun routerManualAction() {
        routerPausedPhone = null
        routerPreparedStart = null
        routerLaunchingLease = null
        routerManualMutable.value += 1
    }

    internal fun routerPhoneSnapshot(): RouterHomePhoneSnapshot = synchronized(commands) {
        val ui = state.value
        val snapshot = runtime.state.value
        RouterHomePhoneSnapshot(ui.selectedProfileId, ui.profileRevision,
            routerConnectedPhone(ui, snapshot, commands.currentGeneration(), pendingRuntimeStart(snapshot), !storageFailed && !profileStoreFault),
            ui.phase == VpnPhase.IDLE && !ui.canDisconnect && !pendingRuntimeStart(snapshot) && !snapshot.hasOwnedTunnel,
            ui.selectedProfileId?.takeIf { ui.isReady && !storageFailed && !profileStoreFault && ui.phase in setOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING) &&
                (pendingRuntimeStart(snapshot) || snapshot.phase in setOf(EnginePhase.CONNECTING, EnginePhase.VALIDATING) && snapshot.commandTicket == commands.currentGeneration() && snapshot.profileId == it) }
                ?.let { RouterPhoneSession(it, ui.profileRevision, commands.currentGeneration()) })
    }

    internal fun pausePhoneForRouter(owner: RouterAutomaticPause): RouterPausedPhone? = synchronized(commands) {
        val current = routerPhoneSnapshot().connected ?: return@synchronized null
        if (current != owner.owner) return@synchronized null
        commands.stop { pendingUiFailure = null; awaitingRuntimeTicket = null; stopRuntime() }
        RouterPausedPhone(owner, commands.currentGeneration(), routerManualMutable.value).also { routerPausedPhone = it }
    }

    internal fun resumePhoneForRouter(receipt: RouterPausedPhone, plan: RouterBoundDialPlan? = null): RouterPhoneSession? {
        val selected = receipt.ownership.owner.profileId
        val ticket = synchronized(commands) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val anotherVpn = manager?.allNetworks?.any { network ->
                manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } != false
            if (!routerResumeOwned(receipt, routerPausedPhone, state.value, runtime.state.value,
                    commands.currentGeneration(), routerManualMutable.value, pendingRuntimeStart(runtime.state.value),
                    !storageFailed && !profileStoreFault, anotherVpn) || VpnService.prepare(context) != null) return@synchronized null
            val profile = stored.profiles.singleOrNull { it.id == selected } ?: return@synchronized null
            if (plan != null) {
                if (!routerDialPlanCurrent(plan, profile, state.value.profileRevision, android.os.SystemClock.elapsedRealtime()) || plan.owner != receipt.ownership.owner) {
                    return@synchronized null
                }
            }
            val reserved = commands.reserveStartIfCurrent(receipt.stopGeneration) ?: return@synchronized null
            routerPausedPhone = null
            routerPreparedStart = plan?.let { reserved to it }
            routerLaunchingLease = routerPreparedStart
            pendingUiFailure = null; awaitingRuntimeTicket = null
            deviceLocation.setTunnelActive(true)
            mutable.update { it.copy(phase = VpnPhase.CONNECTING, hasPendingStart = true, canRestartDuringStop = false, location = null, message = null) }
            reserved
        } ?: return null
        requestStartNow(selected, ticket)
        return RouterPhoneSession(selected, receipt.ownership.owner.profileRevision, ticket)
    }

    internal fun forgetRouterPause(receipt: RouterPausedPhone) = synchronized(commands) {
        if (routerPausedPhone == receipt) routerPausedPhone = null
    }

    internal fun routerConnectedProfile(): com.foxhole.vpn.router.RouterConnectedProfile? = synchronized(commands) {
        val ui = state.value
        val engine = runtime.state.value
        val session = com.foxhole.vpn.router.routerConnectedPhone(ui, engine, commands.currentGeneration(),
            pendingRuntimeStart(engine), !storageFailed && !profileStoreFault) ?: return@synchronized null
        val network = engine.validatedNetworkHandle ?: return@synchronized null
        val profile = stored.profiles.singleOrNull { it.id == session.profileId } ?: return@synchronized null
        com.foxhole.vpn.router.RouterConnectedProfile(profile.id, com.foxhole.vpn.router.routerProfileDigest(profile.normalizedConfigJson),
            session.connectionId.toString(), network)
    }

    internal fun routerImportedProfileReference(configSha256: String): Pair<String, String>? = synchronized(commands) {
        stored.profiles.singleOrNull { it.sourceUrl == null && com.foxhole.vpn.router.routerProfileDigest(it.normalizedConfigJson) == configSha256 }
            ?.let { it.id to configSha256 }
    }

    internal fun routerUserIsForeground(): Boolean = synchronized(visibleActivities) { visibleActivities.isNotEmpty() }

    internal suspend fun routerDialCandidate(owner: RouterPhoneSession,
        resolver: suspend (String) -> List<java.net.InetAddress>): RouterDialCandidate {
        awaitReady()
        fun owned(): Boolean = owner.connectionId == commands.currentGeneration() || routerPausedPhone?.let { receipt ->
            receipt.ownership.owner == owner && routerAutomaticProfilesProtected(receipt, commands.currentGeneration(),
                routerManualMutable.value, state.value.selectedProfileId, state.value.profileRevision)
        } == true
        val (profile, manual) = synchronized(commands) {
            require(!storageFailed && !profileStoreFault && state.value.profileRevision == owner.profileRevision && stored.selectedProfileId == owner.profileId && owned())
            requireNotNull(stored.profiles.singleOrNull { it.id == owner.profileId }) to routerManualMutable.value
        }
        fun checkOwner() = synchronized(commands) {
            require(owned() && routerManualMutable.value == manual && state.value.profileRevision == owner.profileRevision &&
                stored.profiles.singleOrNull { it.id == owner.profileId } == profile)
        }
        return withContext(Dispatchers.IO) { prepareResolvedRouterDialCandidate(profile, owner, resolver, ::checkOwner) }
    }

    private fun routerPreparedConfiguration(profile: VpnProfile, ticket: Long): PreparedVpnConfig {
        val prepared = synchronized(commands) {
            val start = routerPreparedStart ?: return@synchronized null
            require(start.first == ticket && commands.isCurrent(ticket) && runtime.state.value.commandTicket == ticket &&
                routerDialPlanCurrent(start.second, profile, state.value.profileRevision, android.os.SystemClock.elapsedRealtime()))
            routerPreparedStart = null
            start.second.prepared
        }
        return prepared ?: VpnEngineConfig.prepare(profile)
    }

    private fun stopRuntime() {
        val snapshot = runtime.state.value
        mutable.update { it.copy(
            phase = if (snapshot.phase == EnginePhase.IDLE && !snapshot.hasOwnedTunnel) VpnPhase.IDLE else VpnPhase.STOPPING,
            hasOwnedTunnel = snapshot.hasOwnedTunnel,
            hasPendingStart = false,
            canRestartDuringStop = snapshot.phase != EnginePhase.IDLE || snapshot.hasOwnedTunnel,
            location = null,
            message = message(R.string.vpn_error_secure_storage).takeIf { profileStoreFault },
        ) }
        runtime.stop()
    }

    internal fun foregroundChanged(activity: MainActivity, visible: Boolean) = synchronized(visibleActivities) {
        val wasForeground = visibleActivities.isNotEmpty()
        if (visible) visibleActivities.add(activity) else visibleActivities.remove(activity)
        locationForeground = visibleActivities.isNotEmpty()
        deviceLocation.setTunnelActive(state.value.canDisconnect || state.value.isConnected)
        deviceLocation.setForeground(locationStoreReady && visibleActivities.isNotEmpty())
        val foreground = visibleActivities.isNotEmpty()
        ipRefresh.setForeground(foreground)
        if (!wasForeground && foreground) {
            refreshForegroundIp()
            if (subscriptionCheckJob?.isActive != true) subscriptionCheckJob = scope.launch { refreshSubscriptionsAutomatically() }
        }
        dataForegroundChanged(foreground)
    }

    private fun refreshForegroundIp() = synchronized(visibleActivities) {
        if (visibleActivities.isEmpty() || storageFailed || profileStoreFault) return@synchronized
        synchronized(commands) {
            val current = state.value
            val snapshot = runtime.state.value
            if (current.isConnected && snapshot.hasOwnedTunnel && snapshot.commandTicket == current.connectionId &&
                commands.currentGeneration() == current.connectionId && !pendingRuntimeStart(snapshot)
            ) runtime.refreshLocation()
            else if (!current.canDisconnect && !pendingRuntimeStart(snapshot) && !snapshot.hasOwnedTunnel) deviceLocation.refreshNow()
        }
    }

    private fun dataForegroundChanged(visible: Boolean) {
        if (!visible) {
            dataCheckEpoch++
            dataCheckJob?.cancel()
            dataCheckJob = null
            return
        }
        if (dataCheckJob?.isActive == true || dataUpdateJob?.isActive == true) return
        val epoch = ++dataCheckEpoch
        dataCheckJob = scope.launch {
            try {
                awaitReady()
                checkConnectionDataAutomatically()
            } finally {
                synchronized(visibleActivities) { if (epoch == dataCheckEpoch) dataCheckJob = null }
            }
        }
    }

    internal fun updateData() = synchronized(visibleActivities) {
        if (dataUpdateJob?.isActive == true) return@synchronized
        dataCheckEpoch++
        dataCheckJob?.cancel()
        dataCheckJob = null
        dataUpdateJob = scope.launch {
            awaitReady()
            dataOperations.withLock {
                dataUpdater.update()
                if (dataUpdater.state.value.phase in setOf(VpnDataUpdatePhase.UPDATED, VpnDataUpdatePhase.UP_TO_DATE)) {
                    recordConnectionDataAttempt(RefreshOutcome.UNCHANGED)
                }
            }
        }
    }

    internal suspend fun checkConnectionDataAutomatically(): Boolean {
        awaitReady()
        if (storageFailed || profileStoreFault) return false
        return dataOperations.withLock {
            if (!stored.settings.autoCheckConnectionData) return@withLock false
            val stamp = stored.automaticRefresh.connectionData
            val dueStamp = if (dataUpdater.hasVerifiedCheckCache) stamp else stamp.copy(lastSuccessAtMillis = 0)
            val interval = refreshIntervalMillis(stored.settings.connectionDataCheckIntervalHours, 24)
            if (!refreshIsDue(dueStamp, System.currentTimeMillis(), interval)) return@withLock false
            recordConnectionDataAttempt(RefreshOutcome.CHECKING)
            dataUpdater.check()
            val current = dataUpdater.state.value
            val outcome = when (current.phase) {
                VpnDataUpdatePhase.AVAILABLE -> RefreshOutcome.AVAILABLE
                VpnDataUpdatePhase.UP_TO_DATE -> RefreshOutcome.UNCHANGED
                else -> RefreshOutcome.FAILED
            }
            recordConnectionDataAttempt(outcome)
            current.error in setOf(com.foxhole.vpn.updates.VpnDataUpdateError.NETWORK, com.foxhole.vpn.updates.VpnDataUpdateError.STORAGE)
        }
    }

    private suspend fun recordConnectionDataAttempt(outcome: RefreshOutcome) = mutations.withLock {
        if (storageFailed || profileStoreFault) return@withLock
        val before = stored
        val next = before.copy(automaticRefresh = before.automaticRefresh.copy(
            connectionData = before.automaticRefresh.connectionData.attempted(System.currentTimeMillis(), outcome),
        ))
        commitProfileMutation(before, next, storage::update, { !storageFailed && !profileStoreFault }, ::recordProfileStoreFault) { written ->
            synchronized(commands) { publish(written); true }
        }
    }

    internal suspend fun refreshSubscriptionsAutomatically(): SubscriptionRefreshRun {
        awaitReady()
        if (storageFailed || profileStoreFault || !stored.settings.autoRefreshSubscriptions) return SubscriptionRefreshRun()
        return subscriptionOperations.withLock {
            if (storageFailed || profileStoreFault || !stored.settings.autoRefreshSubscriptions) return@withLock SubscriptionRefreshRun()
            val now = System.currentTimeMillis()
            val interval = refreshIntervalMillis(stored.settings.subscriptionRefreshIntervalHours, 12)
            val targets = subscriptionRefreshTargets(stored)
                .filter { refreshIsDue(stored.automaticRefresh.subscriptions[it.key], now, interval) }
                .sortedBy { stored.automaticRefresh.subscriptions[it.key]?.lastAttemptAtMillis ?: 0 }
            val generation = commands.currentGeneration()
            var report = SubscriptionRefreshRun()
            try {
                val completed = withTimeoutOrNull(8 * 60 * 1000L) {
                    for (target in targets) {
                        currentCoroutineContext().ensureActive()
                        if (!stored.settings.autoRefreshSubscriptions || commands.currentGeneration() != generation) {
                            report = report.copy(stale = report.stale + targets.size - report.checked)
                            break
                        }
                        if (automaticProfilesProtected()) {
                            recordSubscriptionAttempt(target, generation, RefreshOutcome.DEFERRED)
                            report = report.copy(checked = report.checked + 1, deferred = report.deferred + 1)
                            continue
                        }
                        if (!recordSubscriptionAttempt(target, generation, RefreshOutcome.CHECKING)) continue
                        val outcome = try {
                            val incoming = withContext(Dispatchers.IO) {
                                fetcher.fetchWithMetadata(target.source).let { fetched ->
                                    importer.importSubscription(fetched.body, target.source, target.allowInsecureTls,
                                        fetched.displayName, fetched.subscriptionExpiresAt)
                                }.also { parsed -> parsed.profiles.forEach(VpnEngineConfig::prepare) }
                            }
                            commitAutomaticSubscription(target, incoming, generation)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            report = report.copy(retry = report.retry || failure is IOException)
                            RefreshOutcome.FAILED
                        }
                        if (outcome in setOf(RefreshOutcome.DEFERRED, RefreshOutcome.STALE, RefreshOutcome.FAILED)) {
                            recordSubscriptionAttempt(target, generation, outcome)
                        }
                        report = report.copy(
                            checked = report.checked + 1,
                            updated = report.updated + if (outcome == RefreshOutcome.UPDATED) 1 else 0,
                            failed = report.failed + if (outcome == RefreshOutcome.FAILED) 1 else 0,
                            deferred = report.deferred + if (outcome == RefreshOutcome.DEFERRED) 1 else 0,
                            stale = report.stale + if (outcome == RefreshOutcome.STALE) 1 else 0,
                        )
                    }
                    true
                }
                if (completed == null) report = report.copy(retry = true)
                report
            } finally { automaticSubscriptionMutable.value = report }
        }
    }

    private fun automaticTargetCurrent(target: SubscriptionRefreshTarget, generation: Long): Boolean =
        !storageFailed && !profileStoreFault && stored.settings.autoRefreshSubscriptions &&
            commands.currentGeneration() == generation && stored.profiles.filter { it.sourceUrl == target.source } == target.profiles

    private suspend fun recordSubscriptionAttempt(target: SubscriptionRefreshTarget, generation: Long, outcome: RefreshOutcome): Boolean = mutations.withLock {
        if (!automaticTargetCurrent(target, generation)) return@withLock false
        val before = stored
        val known = subscriptionRefreshTargets(before).map { it.key }.toSet()
        val stamps = before.automaticRefresh.subscriptions.filterKeys(known::contains).toMutableMap()
        stamps[target.key] = (stamps[target.key] ?: RefreshAttempt()).attempted(System.currentTimeMillis(), outcome)
        val next = before.copy(automaticRefresh = before.automaticRefresh.copy(subscriptions = stamps))
        val result = commitProfileMutation(before, next, storage::update, { automaticTargetCurrent(target, generation) }, ::recordProfileStoreFault) { written ->
            synchronized(commands) {
                if (!automaticTargetCurrent(target, generation)) false else { publish(written); true }
            }
        }
        result == ProfileMutationResult.APPLIED
    }

    private suspend fun commitAutomaticSubscription(target: SubscriptionRefreshTarget, incoming: ImportedVpnProfiles, generation: Long): RefreshOutcome = mutations.withLock {
        if (!automaticTargetCurrent(target, generation)) return@withLock RefreshOutcome.STALE
        if (automaticProfilesProtected()) return@withLock RefreshOutcome.DEFERRED
        val before = stored
        val owner = captureProfileMutationOwner()
        val ownedProfileId = runtime.state.value.takeIf { it.hasOwnedTunnel }?.profileId
        val proposal = proposeSubscriptionRefresh(before, target, incoming, owner, generation, ownedProfileId)
        val proposed = proposal.candidate ?: return@withLock proposal.outcome
        withContext(Dispatchers.Default) { proposed.profiles.filter { it.sourceUrl == target.source }.forEach(VpnEngineConfig::prepare) }
        val known = subscriptionRefreshTargets(proposed).map { it.key }.toSet()
        val stamps = before.automaticRefresh.subscriptions.filterKeys(known::contains).toMutableMap()
        stamps[target.key] = (stamps[target.key] ?: RefreshAttempt()).attempted(System.currentTimeMillis(), proposal.outcome)
        val candidate = proposed.copy(automaticRefresh = proposed.automaticRefresh.copy(subscriptions = stamps))
        fun admitted(): Boolean = automaticTargetCurrent(target, generation) && automaticCandidateSafe(before, candidate)
        val result = commitProfileMutation(before, candidate, storage::update, ::admitted, ::recordProfileStoreFault) { next ->
            synchronized(commands) {
                if (!admitted()) false else { publish(next); true }
            }
        }
        if (result == ProfileMutationResult.APPLIED) proposal.outcome else RefreshOutcome.STALE
    }

    private fun automaticCandidateSafe(before: ProfileStoreState, candidate: ProfileStoreState): Boolean = synchronized(commands) {
        if (automaticProfilesProtected() && (before.profiles != candidate.profiles || before.selectedProfileId != candidate.selectedProfileId)) return@synchronized false
        if (importRequiresDisconnect(before, candidate, captureProfileMutationOwner())) return@synchronized false
        val owned = runtime.state.value.takeIf { it.hasOwnedTunnel }?.profileId ?: return@synchronized true
        before.profiles.firstOrNull { it.id == owned }?.normalizedConfigJson ==
            candidate.profiles.firstOrNull { it.id == owned }?.normalizedConfigJson
    }

    private fun automaticProfilesProtected(): Boolean = synchronized(commands) {
        routerAutomaticProfilesProtected(routerPausedPhone, commands.currentGeneration(), routerManualMutable.value,
            state.value.selectedProfileId, state.value.profileRevision) || routerAutomaticLeaseProtected(routerLeaseOwner?.invoke(),
            commands.currentGeneration(), state.value.selectedProfileId, state.value.profileRevision,
            runtime.state.value.hasOwnedTunnel || pendingRuntimeStart(runtime.state.value)) ||
            routerAutomaticPreparedLeaseProtected(routerLaunchingLease, commands.currentGeneration(), state.value.selectedProfileId,
                state.value.profileRevision, runtime.state.value.hasOwnedTunnel || pendingRuntimeStart(runtime.state.value), android.os.SystemClock.elapsedRealtime())
    }

    override fun selectProfile(id: String) {
        val current = state.value
        if (storageFailed || profileStoreFault || current.isBusy || (current.hasOwnedTunnel && !current.isConnected)) return
        if (current.selectedProfileId == id || current.profiles.none { it.id == id }) return
        synchronized(commands) { routerManualAction() }
        val ticket = if (current.isConnected) commands.reserveStart() else null
        if (ticket != null) commands.applyCurrent(ticket) { mutable.update { it.copy(phase = VpnPhase.STOPPING, location = null) } }
        scope.launch {
            awaitReady()
            if (ticket != null && !commands.isCurrent(ticket)) return@launch
            try {
                persist(ticket) {
                    require(it.profiles.any { profile -> profile.id == id })
                    it.copy(selectedProfileId = id)
                }
                if (ticket != null) requestStart(id, ticket)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket != null) failPendingStart(ticket, R.string.vpn_error_secure_storage)
                else showMessage(R.string.vpn_error_secure_storage)
            }
        }
    }

    override fun refreshLocation() { runtime.refreshLocation() }

    override suspend fun selectProtocol(profileId: String, optionId: String) {
        awaitReady()
        if (protocolChoiceAction(state.value, profileId, optionId) != ProtocolChoiceAction.IMMEDIATE) return
        synchronized(commands) { routerManualAction() }
        val generation = commands.currentGeneration()
        fun idleAndCurrent(): Boolean = !storageFailed && !profileStoreFault && commands.isCurrentWithoutPendingStart(generation) &&
            !state.value.canDisconnect && !state.value.isConnected
        if (!idleAndCurrent()) return
        try {
            mutations.withLock {
                val before = stored
                val profile = before.profiles.firstOrNull { it.id == profileId } ?: return@withLock
                if (!idleAndCurrent() || profile.protocolOptions.none { it.id == optionId } ||
                    state.value.profiles.firstOrNull { it.id == profileId }?.selectedProtocolOptionId == optionId
                ) return@withLock
                val selected = try {
                    withContext(Dispatchers.Default) { profile.withSelectedProtocol(optionId).also(VpnEngineConfig::prepare) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { showMessage(R.string.vpn_error_profile_invalid); return@withLock }
                catch (_: IllegalStateException) { showMessage(R.string.vpn_error_profile_invalid); return@withLock }
                val next = before.copy(
                    profiles = before.profiles.map { if (it.id == profileId) selected else it },
                    selectedProfileId = profileId,
                )
                commitFencedProfileChoice(before, next, storage::update, ::idleAndCurrent, rollbackFailed = ::recordProfileStoreFault) { candidate ->
                    synchronized(commands) {
                        if (!idleAndCurrent()) false
                        else { publish(candidate); true }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showMessage(R.string.vpn_error_secure_storage) }
    }

    override suspend fun confirmProtocolChange(request: VpnProtocolChangeRequest) {
        awaitReady()
        fun stillCurrent(): Boolean = synchronized(commands) {
            val snapshot = runtime.state.value
            !storageFailed && !profileStoreFault && protocolChangeOwnerCurrent(request, state.value, snapshot,
                commands.currentGeneration(), pendingRuntimeStart(snapshot))
        }
        if (!stillCurrent()) return
        synchronized(commands) { routerManualAction() }
        try {
            mutations.withLock {
                if (!stillCurrent()) return@withLock
                val before = stored
                val profile = before.profiles.firstOrNull { it.id == request.profileId } ?: return@withLock
                if (profile.protocolOptions.none { it.id == request.optionId }) return@withLock
                val selected = try {
                    withContext(Dispatchers.Default) { profile.withSelectedProtocol(request.optionId).also(VpnEngineConfig::prepare) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { showMessage(R.string.vpn_error_profile_invalid); return@withLock }
                catch (_: IllegalStateException) { showMessage(R.string.vpn_error_profile_invalid); return@withLock }
                val candidate = before.copy(profiles = before.profiles.map { if (it.id == request.profileId) selected else it })
                commitFencedProfileChoice(before, candidate, storage::update, ::stillCurrent, rollbackFailed = ::recordProfileStoreFault) { next ->
                    synchronized(commands) {
                        if (!stillCurrent()) false
                        else {
                            val ticket = commands.reserveStartIfCurrent(request.connectionId) ?: return@synchronized false
                            pendingUiFailure = null
                            mutable.update { it.copy(phase = VpnPhase.STOPPING, location = null, message = null) }
                            publish(next)
                            requestStart(request.profileId, ticket)
                            true
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showMessage(R.string.vpn_error_secure_storage) }
    }

    override suspend fun exportProfile(profileId: String, optionIds: Set<String>?): String? {
        awaitReady()
        if (storageFailed || profileStoreFault) return null
        val profile = stored.profiles.firstOrNull { it.id == profileId } ?: return null
        return withContext(Dispatchers.Default) {
            if (optionIds == null) exportWholeVpnProfile(profile) else exportVpnProfile(profile, optionIds)
        }
    }

    override fun claimConnectionCue(connectionId: Long): Boolean {
        val current = state.value
        return current.isConnected && current.connectionId == connectionId &&
            commands.currentGeneration() == connectionId && connectionCues.claim(connectionId)
    }

    override suspend fun importProfiles(text: String, allowInsecureTls: Boolean): VpnImportResult {
        awaitReady()
        synchronized(commands) { routerManualAction() }
        if (storageFailed || profileStoreFault) return VpnImportResult.Failure(message(R.string.vpn_error_secure_storage))
        return try {
            val incoming = withContext(Dispatchers.IO) {
                val url = importer.subscriptionUrlOrNull(text, allowInsecureTls)
                val parsed = if (url == null) importer.import(text, allowInsecureTls)
                    else fetcher.fetchWithMetadata(url).let { fetched ->
                        importer.importSubscription(fetched.body, url, allowInsecureTls, fetched.displayName, fetched.subscriptionExpiresAt)
                    }
                parsed.also { it.profiles.forEach(VpnEngineConfig::prepare) }
            }
            merge(incoming)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: InsecureTlsConsentRequired) { VpnImportResult.NeedsInsecureTlsConsent }
        catch (_: ActiveProfileImportException) { VpnImportResult.Failure(message(R.string.vpn_error_active_profile_import)) }
        catch (_: FoxCoreConfigTranslationException) { VpnImportResult.Failure(message(R.string.vpn_error_profile_invalid)) }
        catch (_: Exception) { VpnImportResult.Failure(message(R.string.vpn_error_import_failed)) }
    }

    override suspend fun refreshSubscription(profileId: String): VpnImportResult {
        synchronized(commands) { routerManualAction() }
        awaitReady()
        if (storageFailed || profileStoreFault) return VpnImportResult.Failure(message(R.string.vpn_error_secure_storage))
        val profile = stored.profiles.firstOrNull { it.id == profileId }
            ?: return VpnImportResult.Failure(message(R.string.vpn_error_profile_unavailable))
        val url = profile.sourceUrl
            ?: return VpnImportResult.Failure(message(R.string.vpn_error_subscription_failed))
        return try {
            val incoming = withContext(Dispatchers.IO) {
                fetcher.fetchWithMetadata(url).let { fetched ->
                    importer.importSubscription(fetched.body, url, profile.insecureTlsConsent, fetched.displayName, fetched.subscriptionExpiresAt)
                }
                    .also { it.profiles.forEach(VpnEngineConfig::prepare) }
            }
            merge(incoming, replaceSource = url)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { VpnImportResult.Failure(message(R.string.vpn_error_subscription_failed)) }
    }

    private suspend fun merge(incoming: ImportedVpnProfiles, replaceSource: String? = null): VpnImportResult {
        var added = 0
        var updated = 0
        persist(protectRunningProfile = replaceSource == null) { before ->
            val next = before.profiles.filterNot { replaceSource != null && it.sourceUrl == replaceSource }.toMutableList()
            incoming.profiles.forEach { profile ->
                val existing = before.profiles.firstOrNull {
                    it.id == profile.id || (profile.sourceUrl == null && it.normalizedConfigJson == profile.normalizedConfigJson)
                }
                if (existing == null) added++ else updated++
                val retainedOption = existing?.selectedProtocolOptionId?.takeIf { option -> profile.protocolOptions.any { it.id == option } }
                val selected = (if (retainedOption != null) profile.withSelectedProtocol(retainedOption) else profile)
                    .copy(id = existing?.id ?: profile.id)
                next.removeAll { it.id == selected.id }
                next += selected
            }
            require(next.size <= 256)
            val selection = before.selectedProfileId?.takeIf { id -> next.any { it.id == id } } ?: next.firstOrNull()?.id
            val checkedSources = incoming.profiles.mapNotNull { it.sourceUrl }.distinct()
            val known = next.mapNotNull { it.sourceUrl }.map(::subscriptionSourceKey).toSet()
            val stamps = before.automaticRefresh.subscriptions.filterKeys(known::contains).toMutableMap()
            checkedSources.forEach { source ->
                val key = subscriptionSourceKey(source)
                stamps[key] = (stamps[key] ?: RefreshAttempt()).attempted(System.currentTimeMillis(), RefreshOutcome.UPDATED)
            }
            before.copy(profiles = next, selectedProfileId = selection,
                automaticRefresh = before.automaticRefresh.copy(subscriptions = stamps))
        }
        return VpnImportResult.Success(added, updated, incoming.rejected)
    }

    override suspend fun removeProfile(id: String) {
        synchronized(commands) { routerManualAction() }
        awaitReady()
        if (state.value.canDisconnect || state.value.isConnected) {
            showMessage(R.string.vpn_error_active_profile_remove)
            return
        }
        try {
            persist { before ->
                val profiles = before.profiles.filterNot { it.id == id }
                before.copy(profiles = profiles, selectedProfileId = before.selectedProfileId?.takeIf { it != id } ?: profiles.firstOrNull()?.id)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showMessage(R.string.vpn_error_secure_storage) }
    }

    override suspend fun removeProfile(id: String, expectedProfileRevision: Long) {
        synchronized(commands) { routerManualAction() }
        awaitReady()
        val generation = commands.currentGeneration()
        fun stillCurrent(): Boolean = synchronized(commands) {
            !storageFailed && !profileStoreFault && commands.isCurrentWithoutPendingStart(generation) &&
                !pendingRuntimeStart(runtime.state.value) && profileRemovalCurrent(state.value, id, expectedProfileRevision)
        }
        if (!stillCurrent()) return
        try {
            mutations.withLock {
                if (!stillCurrent()) return@withLock
                val before = stored
                val profiles = before.profiles.filterNot { it.id == id }
                val candidate = before.copy(profiles = profiles,
                    selectedProfileId = before.selectedProfileId?.takeIf { it != id } ?: profiles.firstOrNull()?.id)
                commitFencedProfileChoice(before, candidate, storage::update, ::stillCurrent, rollbackFailed = ::recordProfileStoreFault) { next ->
                    synchronized(commands) {
                        if (!stillCurrent()) false else { publish(next); true }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showMessage(R.string.vpn_error_secure_storage) }
    }

    override val appLanguageSettingsReady: Boolean
        get() = ready.isCompleted && !storageFailed && !profileStoreFault

    override suspend fun updateAppLanguage(language: VpnLanguage): Boolean = updateAppLanguageIfCurrent(language, null)

    internal suspend fun updateAppLanguageIfCurrent(language: VpnLanguage, expected: VpnLanguage?): Boolean {
        awaitReady()
        if (!appLanguageSettingsReady) return false
        return try {
            persist(expectedAppLanguage = expected) { it.copy(settings = it.settings.copy(language = language)) }
            if (!appLanguageSettingsReady || settings.value.language != language) return false
            withContext(Dispatchers.Main.immediate) { drivers.keys.forEach { it.refreshOwnedNotification() } }
            appLanguageSettingsReady && settings.value.language == language
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showMessage(R.string.vpn_error_secure_storage); false }
    }

    override suspend fun updateSettings(settings: VpnSettings) {
        awaitReady()
        try {
            persist { it.copy(settings = settings) }
            withContext(Dispatchers.Main.immediate) { drivers.keys.forEach { it.refreshOwnedNotification() } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showMessage(R.string.vpn_error_secure_storage) }
    }

    private suspend fun persist(preservingCommand: Long? = null, protectRunningProfile: Boolean = false, expectedAppLanguage: VpnLanguage? = null, transform: (ProfileStoreState) -> ProfileStoreState) {
        if (storageFailed || profileStoreFault) throw ProfileMutationStorageFaultException()
        mutations.withLock {
            if (profileStoreFault) throw ProfileMutationStorageFaultException()
            val before = stored
            if (expectedAppLanguage != null && before.settings.language != expectedAppLanguage) return@withLock
            val owner = captureProfileMutationOwner()
            val candidate = withContext(Dispatchers.Default) { transform(before) }
            val selectedIdChanged = before.selectedProfileId != candidate.selectedProfileId
            val selectedConfigChanged = before.profiles.firstOrNull { it.id == before.selectedProfileId }?.normalizedConfigJson !=
                candidate.profiles.firstOrNull { it.id == candidate.selectedProfileId }?.normalizedConfigJson
            val changesSelection = selectedIdChanged || selectedConfigChanged
            if (protectRunningProfile && importRequiresDisconnect(before, candidate, owner)) throw ActiveProfileImportException()
            val action = profileMutationAction(owner, selectedIdChanged, selectedConfigChanged, preservingCommand)
            val result = commitProfileMutation(before, candidate, storage::update, {
                val current = captureProfileMutationOwner()
                (!protectRunningProfile || !importRequiresDisconnect(before, candidate, current)) &&
                    profileMutationAdmitted(owner, current, changesSelection, preservingCommand)
            }, onRollbackFailure = ::recordProfileStoreFault) { next ->
                synchronized(commands) {
                    if (protectRunningProfile && importRequiresDisconnect(before, next, captureProfileMutationOwner())) false
                    else publishProfileMutation(next, owner, changesSelection, action, preservingCommand)
                }
            }
            if (result == ProfileMutationResult.SUPERSEDED) {
                if (protectRunningProfile && importRequiresDisconnect(before, candidate, captureProfileMutationOwner())) throw ActiveProfileImportException()
                throw ProfileMutationSupersededException()
            }
        }
    }

    private fun captureProfileMutationOwner(): ProfileMutationOwner = synchronized(commands) {
        val current = state.value
        ProfileMutationOwner(commands.currentGeneration(), current.phase, current.hasOwnedTunnel, pendingRuntimeStart(runtime.state.value))
    }

    private fun pendingRuntimeStart(snapshot: EngineSnapshot): Boolean {
        if (awaitingRuntimeTicket != null && snapshot.commandTicket == awaitingRuntimeTicket) awaitingRuntimeTicket = null
        return commands.hasPendingStart() || awaitingRuntimeTicket == commands.currentGeneration()
    }

    private fun recordProfileStoreFault() = synchronized(commands) {
        profileStoreFault = true
        val snapshot = runtime.state.value
        mutable.update { projectVpnRuntime(
            it, snapshot, commands.currentGeneration(), pendingRuntimeStart(snapshot), pendingUiFailure,
            message(R.string.vpn_error_secure_storage), { failure -> message(errorResource(failure)) }, ::message,
        ) }
    }

    private fun publishProfileMutation(
        candidate: ProfileStoreState,
        owner: ProfileMutationOwner,
        changesSelection: Boolean,
        action: ProfileMutationAction,
        preservingCommand: Long?,
    ): Boolean = synchronized(commands) {
        if (!profileMutationAdmitted(owner, captureProfileMutationOwner(), changesSelection, preservingCommand)) return@synchronized false
        val replacement = when (action) {
            ProfileMutationAction.KEEP -> null
            ProfileMutationAction.STOP -> {
                commands.stop(preservingCommand) {
                    mutable.update { it.copy(phase = VpnPhase.STOPPING, location = null) }
                    runtime.stop()
                }
                null
            }
            ProfileMutationAction.RECONNECT -> {
                val ticket = commands.reserveStartIfCurrent(owner.generation) ?: return@synchronized false
                mutable.update { it.copy(phase = VpnPhase.STOPPING, location = null) }
                ticket
            }
        }
        publish(candidate)
        if (replacement != null) requestStart(requireNotNull(candidate.selectedProfileId), replacement)
        true
    }

    private fun publish(value: ProfileStoreState) {
        val revision = state.value.profileRevision + if (stored.profiles != value.profiles || stored.selectedProfileId != value.selectedProfileId) 1 else 0
        stored = value
        settingsMutable.value = value.settings
        mutable.update { it.copy(
            isReady = !profileStoreFault,
            profiles = value.profiles.map { profile ->
                VpnProfileSummary(
                    profile.id, profile.name, profile.protocol, profile.sourceUrl != null,
                    protocolOptions = profile.protocolOptions.map { VpnProtocolSummary(it.id, it.displayName, it.protocolHint.name) },
                    selectedProtocolOptionId = profile.selectedProtocolOptionId
                        ?: profile.protocolOptions.firstOrNull { it.normalizedConfigJson == profile.normalizedConfigJson }?.id,
                    subscriptionExpiresAt = profile.subscriptionExpiresAt,
                )
            },
            selectedProfileId = value.selectedProfileId,
            profileRevision = revision,
        ) }
        try { refreshWork.apply(value.settings, value.profiles.any { it.sourceUrl != null }) }
        catch (_: RuntimeException) { automaticSubscriptionMutable.value = SubscriptionRefreshRun(failed = 1, retry = true) }
        if (!value.settings.autoRefreshSubscriptions) subscriptionCheckJob?.cancel()
        if (!value.settings.autoCheckConnectionData) dataCheckJob?.cancel()
    }

    internal fun serviceCommand(service: VpnTunnelService, profileId: String?, ticket: Long, startId: Int) {
        commands.applyCurrent(ticket) {
            if (commands.hasPendingStart()) service.recordPendingCommand(ticket)
        }
        scope.launch {
            awaitReady()
            val accepted = commands.dispatch(ticket) {
                val profile = stored.profiles.firstOrNull { it.id == profileId }
                if (!licenseAccepted()) {
                    failPendingStart(ticket, R.string.vpn_license_required, stopExisting = false)
                } else if (profileStoreFault) {
                    failPendingStart(ticket, R.string.vpn_error_secure_storage, stopExisting = false)
                } else if (storageFailed || profile == null || profile.id != stored.selectedProfileId || VpnService.prepare(context) != null) {
                    failPendingStart(ticket, R.string.vpn_error_profile_unavailable)
                } else {
                    service.recordAcceptedCommand(ticket)
                    val driver = drivers.getOrPut(service) { AndroidEngineDriver(service) { profile -> routerPreparedConfiguration(profile, service.latestCommandTicket) } }
                    awaitingRuntimeTicket = ticket
                    runtime.connect(profile, driver, commandTicket = ticket)
                }
            }
            if (!accepted || !drivers.containsKey(service)) finishUnownedService(service, startId)
        }
    }

    internal fun serviceStop(service: VpnTunnelService, startId: Int) {
        disconnect()
        finishUnownedService(service, startId)
    }

    private fun finishUnownedService(service: VpnTunnelService, startId: Int) {
        scope.launch(Dispatchers.Main.immediate) {
            if (!drivers.containsKey(service)) service.finishOwnedService(startId)
        }
    }

    internal fun serviceRevoked(service: VpnTunnelService) = synchronized(commands) {
        routerManualAction()
        val driver = drivers[service]
        if (driver != null) commands.stop { runtime.stop(driver) }
        else commands.cancelPending(service.pendingCommandTicket) { stopRuntime() }
        Unit
    }

    internal fun underlyingChanged() { runtime.networkChanged() }

    internal fun protectRouterSocket(socket: Socket): Boolean = routerSocketOwners.protect(socket)

    internal fun isRouterSocketCurrent(socket: Socket): Boolean = routerSocketOwners.isCurrent(socket)

    private fun captureRouterSocketOwner(): RouterSocketOwnerSnapshot<VpnTunnelService, AndroidEngineDriver>? = synchronized(commands) {
        val snapshot = runtime.state.value
        drivers.entries.singleOrNull()?.takeIf {
            commands.isCurrent(it.key.latestCommandTicket) || snapshot.hasOwnedTunnel && snapshot.commandTicket == it.key.latestCommandTicket
        }?.let { RouterSocketOwnerSnapshot(it.key, it.value, it.key.latestCommandTicket, commands.currentGeneration()) }
    }

    private fun finishService(driver: EngineDriver) {
        val service = drivers.entries.firstOrNull { it.value === driver }?.key ?: return
        val startId = service.latestStartId
        drivers.remove(service, driver)
        scope.launch(Dispatchers.Main.immediate) {
            if (!drivers.containsKey(service)) service.finishOwnedService(startId)
        }
    }

    private fun terminatePoisonedProcess(driver: EngineDriver) {
        driver.abortPendingTun()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { context.getSystemService(android.app.ActivityManager::class.java)
                ?.setProcessStateSummary("foxhole_vpn_fail_closed".toByteArray()) }
        }
        android.util.Log.e("FoxHoleVpn", "Native cleanup did not settle; terminating the owning process")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun message(resource: Int): String = context.withVpnLocale(settings.value.language).getString(resource)
    private fun showMessage(resource: Int) { mutable.update { it.copy(message = message(resource)) } }

    private class ActiveProfileImportException : IllegalStateException("active_profile_import_requires_disconnect")

    private fun errorResource(failure: EngineFailure): Int = when (failure) {
        EngineFailure.START -> R.string.vpn_error_connection_failed
        EngineFailure.VALIDATION -> R.string.vpn_error_validation_failed
        EngineFailure.NATIVE_UNAVAILABLE -> R.string.vpn_error_native_unavailable
        EngineFailure.PROFILE -> R.string.vpn_error_profile_invalid
        EngineFailure.STOP -> R.string.vpn_error_tunnel_stop
        EngineFailure.GEO -> R.string.vpn_error_geo_unavailable
    }
}

internal fun importRequiresDisconnect(before: ProfileStoreState, candidate: ProfileStoreState, owner: ProfileMutationOwner): Boolean {
    val selectionChanged = before.selectedProfileId != candidate.selectedProfileId ||
        before.profiles.firstOrNull { it.id == before.selectedProfileId }?.normalizedConfigJson !=
        candidate.profiles.firstOrNull { it.id == candidate.selectedProfileId }?.normalizedConfigJson
    return selectionChanged && (owner.hasOwnedTunnel || owner.hasPendingStart || owner.phase in setOf(
        VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.CONNECTED, VpnPhase.STOPPING,
    ))
}
