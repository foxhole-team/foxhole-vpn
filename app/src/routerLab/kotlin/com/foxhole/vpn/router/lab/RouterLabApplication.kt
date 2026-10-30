package com.foxhole.vpn.router.lab

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.foxhole.vpn.BuildConfig
import com.foxhole.vpn.DefaultVpnController
import com.foxhole.vpn.router.EncryptedRouterPairingStore
import com.foxhole.vpn.router.RouterHomeAutomationManager
import com.foxhole.vpn.router.RouterHomePhoneController
import com.foxhole.vpn.router.RouterHttpProvisioner
import com.foxhole.vpn.router.RouterLiveHomeLink
import com.foxhole.vpn.router.RouterPhoneLeaseKeeper
import com.foxhole.vpn.router.RouterSocketProtection
import com.foxhole.vpn.router.RouterWifiConnection
import com.foxhole.vpn.router.WifiRouterWizardTransport
import com.foxhole.vpn.router.RouterHomeAutomationService
import com.foxhole.vpn.router.requireRouterLocalNetworkAccess
import com.foxhole.vpn.router.routerHomeNotificationsAvailable
import com.foxhole.vpn.router.routerLocalNetworkAccessGranted
import com.foxhole.vpn.runtime.VpnServiceRuntimeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger

internal class RouterLabApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resumedActivities = AtomicInteger()
    internal lateinit var controller: DefaultVpnController
        private set
    internal lateinit var store: EncryptedRouterPairingStore
        private set
    internal lateinit var binding: VpnServiceRuntimeBinding
        private set
    internal lateinit var leases: RouterPhoneLeaseKeeper
        private set
    @Volatile internal var lastStartedEpoch: Long = -1
        private set
    @Volatile internal var persistedHomeEnabled = false
        private set
    internal val resumed get() = resumedActivities.get() > 0

    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.BUILD_TYPE == "routerLab" && packageName == "com.foxhole.vpn.routerlab")
        controller = DefaultVpnController(this, scope)
        store = EncryptedRouterPairingStore(this)
    }

    internal fun activityResumed() { resumedActivities.incrementAndGet() }
    internal fun activityPaused() { check(resumedActivities.decrementAndGet() >= 0) }

    internal fun bindFixture(): RouterHomeAutomationManager {
        check(!::binding.isInitialized || !binding.home.isEnabled && leases.owner == null)
        val network = requireNotNull(getSystemService(ConnectivityManager::class.java))
        val protection = RouterSocketProtection(controller::protectRouterSocket)
        val access = { requireRouterLocalNetworkAccess(this) }
        val phone = object : RouterHomePhoneController {
            override val changes = controller.state
            override val manualGeneration = controller.routerManualGeneration
            override fun snapshot() = controller.routerPhoneSnapshot()
            override fun pause(owner: com.foxhole.vpn.router.RouterAutomaticPause) = controller.pausePhoneForRouter(owner)
            override fun resume(receipt: com.foxhole.vpn.router.RouterPausedPhone, plan: com.foxhole.vpn.router.RouterBoundDialPlan?) = controller.resumePhoneForRouter(receipt, plan)
            override suspend fun dialCandidate(owner: com.foxhole.vpn.router.RouterPhoneSession, resolver: suspend (String) -> List<java.net.InetAddress>) = controller.routerDialCandidate(owner, resolver)
            override fun forget(receipt: com.foxhole.vpn.router.RouterPausedPhone) = controller.forgetRouterPause(receipt)
        }
        leases = RouterPhoneLeaseKeeper(scope, phone, SystemClock::elapsedRealtime) { routerLocalNetworkAccessGranted(this) }
        val monitor = RouterHomeAutomationManager(true, scope, phone,
            linkFactory = { RouterLiveHomeLink(network,
                WifiRouterWizardTransport(network, socketProtection = protection, accessCheck = access), store,
                RouterHttpProvisioner(localAddress = { identity ->
                    requireNotNull(RouterWifiConnection.select(network, identity.endpoint, protection, access).localAddress.hostAddress)
                }, pairingStore = store), protection, access, phone, SystemClock::elapsedRealtime) },
            elapsedMs = SystemClock::elapsedRealtime, userForeground = { resumed },
            notificationAccess = { routerHomeNotificationsAvailable(this) },
            localNetworkAccess = { routerLocalNetworkAccessGranted(this) },
            startForeground = { epoch ->
                lastStartedEpoch = epoch
                ContextCompat.startForegroundService(this, Intent(this, RouterHomeAutomationService::class.java)
                    .setAction(RouterHomeAutomationService.ACTION_START)
                    .putExtra(RouterHomeAutomationService.EXTRA_REQUEST_EPOCH, epoch))
            },
            stopForeground = { epoch -> startService(Intent(this, RouterHomeAutomationService::class.java)
                .setAction(RouterHomeAutomationService.ACTION_STOP).putExtra(RouterHomeAutomationService.EXTRA_REQUEST_EPOCH, epoch)) },
            persistOff = { persistedHomeEnabled = false }, persistOn = { pairingId ->
                val saved = store.read() ?: error("Lab pairing unavailable")
                try { check(saved.id == pairingId); persistedHomeEnabled = true } finally { saved.clear() }
            },
            installPreparation = { controller.routerStartPreparation = it }, retainedLeases = leases)
        controller.routerLeaseOwner = { monitor.leaseOwner ?: leases.owner }
        binding = object : VpnServiceRuntimeBinding {
            override val controller get() = this@RouterLabApplication.controller
            override val home = monitor
            override val activity = RouterLabActivity::class.java
        }
        return monitor
    }
}
