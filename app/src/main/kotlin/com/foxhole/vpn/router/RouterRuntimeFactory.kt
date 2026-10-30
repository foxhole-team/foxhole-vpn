package com.foxhole.vpn.router

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.PersistableBundle
import com.foxhole.vpn.DefaultVpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnApplication
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import androidx.core.content.ContextCompat
import android.os.SystemClock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

internal class RouterRuntime(
    val wizard: RouterWizardCoordinator,
    val control: RouterControlCoordinator,
    val pairingStore: RouterPairingStore,
    val preferenceStore: RouterControlPreferenceStore,
    val home: RouterHomeAutomationManager,
    val profileProbe: RouterProfileAutoProbe,
) : AutoCloseable {
    override fun close() { wizard.close(); control.close() }
}

internal fun createRouterRuntime(context: Context, controller: DefaultVpnController, scope: CoroutineScope): RouterRuntime {
    val application = context.applicationContext
    val manager = requireNotNull(application.getSystemService(ConnectivityManager::class.java))
    val pairing = EncryptedRouterPairingStore(application)
    val preferences = LocalRouterControlPreferenceStore(application, pairings = pairing)
    val protection = object : RouterSocketProtection {
        override fun protect(socket: java.net.Socket) = controller.protectRouterSocket(socket)
        override fun isCurrent(socket: java.net.Socket) = controller.isRouterSocketCurrent(socket)
    }
    val access = { requireRouterLocalNetworkAccess(application) }
    val transport = WifiRouterWizardTransport(manager, socketProtection = protection, accessCheck = access)
    val provisioner = RouterHttpProvisioner(localAddress = { identity ->
        requireNotNull(RouterWifiConnection.select(manager, identity.endpoint, protection, access).localAddress.hostAddress)
    }, pairingStore = pairing)
    val phone = object : RouterHomePhoneController {
        override val changes = controller.state
        override val manualGeneration = controller.routerManualGeneration
        override fun snapshot() = controller.routerPhoneSnapshot()
        override fun pause(owner: RouterAutomaticPause) = controller.pausePhoneForRouter(owner)
        override fun resume(receipt: RouterPausedPhone, plan: RouterBoundDialPlan?) = controller.resumePhoneForRouter(receipt, plan)
        override suspend fun dialCandidate(owner: RouterPhoneSession, resolver: suspend (String) -> List<java.net.InetAddress>) = controller.routerDialCandidate(owner, resolver)
        override fun forget(receipt: RouterPausedPhone) = controller.forgetRouterPause(receipt)
    }
    val leases = RouterPhoneLeaseKeeper(scope, phone, SystemClock::elapsedRealtime) { routerLocalNetworkAccessGranted(application) }
    val home = RouterHomeAutomationManager(available = ROUTER_HOME_ACCEPTED, scope, phone,
        linkFactory = { RouterLiveHomeLink(manager,
            WifiRouterWizardTransport(manager, socketProtection = protection, accessCheck = access),
            pairing, RouterHttpProvisioner(localAddress = { identity ->
                requireNotNull(RouterWifiConnection.select(manager, identity.endpoint, protection, access).localAddress.hostAddress)
            }, pairingStore = pairing), protection, access, phone, SystemClock::elapsedRealtime) },
        elapsedMs = SystemClock::elapsedRealtime, userForeground = controller::routerUserIsForeground,
        notificationAccess = { routerHomeNotificationsAvailable(application) },
        localNetworkAccess = { routerLocalNetworkAccessGranted(application) },
        startForeground = { epoch -> ContextCompat.startForegroundService(application,
            Intent(application, RouterHomeAutomationService::class.java).setAction(RouterHomeAutomationService.ACTION_START)
                .putExtra(RouterHomeAutomationService.EXTRA_REQUEST_EPOCH, epoch)) },
        stopForeground = { epoch -> application.startService(Intent(application, RouterHomeAutomationService::class.java)
            .setAction(RouterHomeAutomationService.ACTION_STOP).putExtra(RouterHomeAutomationService.EXTRA_REQUEST_EPOCH, epoch)) },
        persistOff = {
            pairing.read()?.let { saved -> try {
                val old = preferences.read(saved.id)
                if (old.homeAutodetection) preferences.save(old.copy(homeAutodetection = false))
            } finally { saved.clear() } }
        }, installPreparation = { controller.routerStartPreparation = it }, persistOn = { pairingId ->
            val saved = pairing.read() ?: throw RouterHomePairingChanged()
            try {
                if (saved.id != pairingId) throw RouterHomePairingChanged()
                val old = preferences.read(saved.id)
                if (!old.enabled) throw RouterApiFailure(RouterApiError.UNSUPPORTED)
                preferences.save(old.copy(homeAutodetection = true))
            } finally { saved.clear() }
        }, retainedLeases = leases)
    controller.routerLeaseOwner = { home.leaseOwner ?: leases.owner }
    val release = readRouterReleaseAnchor(application)
    val setup = release?.let { WifiRouterSetupService.forWifi(manager, it, protection, access) }
    val export = RouterPhoneProfileExport(controller)
    val routerImports = VpnRouterProfileImports(controller, pairing)
    val wizard = RouterWizardCoordinator(transport, pairing, Build.MODEL.take(128).ifBlank { "Android" }, setup, provisioner,
        exportProfile = export::selected, importRemoteProfile = { uri ->
            val result = controller.importProfiles(uri, allowInsecureTls = false)
            result is VpnImportResult.Success && result.rejected == 0 && result.added + result.updated > 0
        }, preferenceStore = preferences, beforeRouterSelection = home::routerSelectionChanged,
        importAuthenticatedProfile = routerImports::authenticated)
    val control = RouterControlCoordinator(PairedRouterManagementTransport(transport, manager, access), pairing, preferences, provisioner,
        homeAutomationAvailable = home.available, applyHomeAutomation = home::setEnabled, homeAutomationActive = { home.isEnabled }, copyProfile = { value ->
        withContext(Dispatchers.Main) {
            val clipboard = requireNotNull(application.getSystemService(ClipboardManager::class.java))
            val clip = ClipData.newPlainText("VPN", value)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            clipboard.setPrimaryClip(clip)
        }
    }, importProfile = { value ->
        val result = controller.importProfiles(value, allowInsecureTls = false)
        result is VpnImportResult.Success && result.rejected == 0 && result.added + result.updated > 0
    }, beforeRouterSelection = home::routerSelectionChanged, importAuthenticatedProfile = routerImports::authenticated)
    scope.launch { home.status.collect { status -> control.homeMonitoringChanged(home.isEnabled && status != RouterHomeMonitorState.OFF) } }
    val connectedProfiles = combine(controller.state, (application as VpnApplication).licenseConsent.state) { _, license ->
        if (license.accepted) controller.routerConnectedProfile() else null
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    val profileProbe = RouterProfileAutoProbe(scope, manager, pairing, preferences, connectedProfiles, access)
    return RouterRuntime(wizard, control, pairing, preferences, home, profileProbe)
}

// Enabled only after firmware handoff and physical foreground-service acceptance.
private const val ROUTER_HOME_ACCEPTED = false

private fun readRouterReleaseAnchor(context: Context): RouterSetupRelease? {
    val bytes = try {
        context.assets.open("router-release-anchor.json").use { stream ->
            val contents = stream.readBytes()
            require(contents.size in 1..4096)
            contents
        }
    } catch (_: FileNotFoundException) { return null }
    val anchor = routerInstallDocument(bytes, 4096)
    require(anchor.installNumber("schema") == 1)
    return GithubRouterSetupRelease(anchor.installText("baseUrl"), anchor.installText("manifestSha256"))
}
