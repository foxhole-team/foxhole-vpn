package com.foxhole.vpn.router.lab

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.router.RouterEndpoint
import com.foxhole.vpn.router.RouterHomeAutomationManager
import com.foxhole.vpn.router.RouterHomeAutomationService
import com.foxhole.vpn.router.RouterHomeMonitorState
import com.foxhole.vpn.router.RouterHttpProvisioner
import com.foxhole.vpn.router.RouterLocalNetworkPermissionRequired
import com.foxhole.vpn.router.RouterPairing
import com.foxhole.vpn.router.RouterPhoneStatus
import com.foxhole.vpn.router.RouterSetupFailure
import com.foxhole.vpn.router.RouterSocketProtection
import com.foxhole.vpn.router.RouterTlsIdentity
import com.foxhole.vpn.router.RouterWifiConnection
import com.foxhole.vpn.router.WifiRouterWizardTransport
import com.foxhole.vpn.router.requireBoundedRouterJson
import com.foxhole.vpn.router.requireRouterLocalNetworkAccess
import com.foxhole.vpn.router.routerHomeNotificationsAvailable
import com.foxhole.vpn.router.routerLocalNetworkAccessGranted
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RouterHomeLifecycleAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RouterLabApplication
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val network get() = requireNotNull(app.getSystemService(ConnectivityManager::class.java))
    private val notifications get() = requireNotNull(app.getSystemService(NotificationManager::class.java))

    @Before fun requireExplicitIsolatedPackageAndPublicFixtureReceipt() {
        assertEquals("com.foxhole.vpn.routerlab", instrumentation.targetContext.packageName)
        assertTrue(Build.VERSION.SDK_INT >= 35)
        assertEquals("home-lab-20261011", arguments.getString("routerOwnedLab"))
        for (key in listOf("routerInstalledManifestSha256", "routerCoreProducerSha256")) publicArgument(key)
    }

    @Test fun deniedNotificationsCannotAdmitOrPromoteTheActualService() = runBlocking {
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(routerHomeNotificationsAvailable(app))
        ActivityScenario.launch<RouterLabActivity>(Intent(app, RouterLabActivity::class.java)).use {
            val home = app.bindFixture()
            val previous = app.lastStartedEpoch
            try { home.setEnabled(true); error("Denied notification permission admitted Home") }
            catch (_: RouterSetupFailure) { }
            assertFalse(home.isEnabled); assertFalse(app.persistedHomeEnabled)
            assertEquals(previous, app.lastStartedEpoch); assertFalse(homeNotificationPresent())
        }
    }

    @Test fun deniedApi37LanPermissionCannotInspectOrPromoteTheActualService() = runBlocking {
        assertTrue(Build.VERSION.SDK_INT >= 37)
        assertFalse(routerLocalNetworkAccessGranted(app))
        assertTrue(routerHomeNotificationsAvailable(app))
        ActivityScenario.launch<RouterLabActivity>(Intent(app, RouterLabActivity::class.java)).use {
            val home = app.bindFixture()
            val previous = app.lastStartedEpoch
            try { home.setEnabled(true); error("Denied LAN permission admitted Home") }
            catch (_: RouterLocalNetworkPermissionRequired) { }
            assertFalse(home.isEnabled); assertFalse(app.persistedHomeEnabled)
            assertEquals(previous, app.lastStartedEpoch); assertFalse(homeNotificationPresent())
        }
    }

    @Test fun isolatedLabVpnConsentIsRequestedThroughTheActualSystemDialog() = runBlocking {
        ActivityScenario.launch<RouterLabActivity>(Intent(app, RouterLabActivity::class.java)).use { activity ->
            activity.onActivity { it.requestVpnConsent() }
            marker("await-vpn-consent")
            await(60000) { VpnService.prepare(app) == null }
            assertFalse(app.controller.state.value.hasOwnedTunnel)
            marker("vpn-consent-granted")
        }
    }

    @Test fun genuinePinnedEnrollmentProvidesFreshPairOwnedProofWithoutStartingPhoneVpn() = withFixture { fixture ->
        val status = fixture.status()
        assertEquals(fixture.pair.id, status.pairingId)
        assertNotNull(status.lanDeviceId)
        assertTrue(status.handoffKnown); assertEquals(null, status.handoff)
        assertNotNull(status.proof)
        assertTrue(requireNotNull(status.proof).tunnelValidated)
        assertTrue(requireNotNull(status.proof).thisPhoneRouted)
        assertFalse(app.controller.state.value.hasOwnedTunnel)
        assertFalse(fixture.home.isEnabled); assertFalse(homeNotificationPresent())
    }

    @Test fun realForegroundPromotionSurvivesBackgroundAndQuickDisableUsesItsOwnedEpoch() = withFixture { fixture ->
        fixture.home.setEnabled(true)
        awaitHome(fixture.home)
        val active = requireNotNull(notifications.activeNotifications.singleOrNull { it.id == 1002 })
        assertTrue(active.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(requireNotNull(active.notification.contentIntent).isImmutable)
        val action = requireNotNull(active.notification.actions).single()
        assertTrue(action.actionIntent.isImmutable)
        fixture.activity.moveToState(Lifecycle.State.CREATED)
        delay(8000)
        assertFalse(app.resumed); assertTrue(fixture.home.isEnabled); assertTrue(homeNotificationPresent())
        action.actionIntent.send()
        await(10000) { !fixture.home.isEnabled && !homeNotificationPresent() }
        assertFalse(app.persistedHomeEnabled)
        assertFalse(app.controller.state.value.hasOwnedTunnel)
    }

    @Test fun queuedOldServiceStartAndStopCannotRetireTheNewRequest() = withFixture { fixture ->
        fixture.home.setEnabled(true); awaitHome(fixture.home)
        val old = app.lastStartedEpoch
        val oldAction = requireNotNull(notifications.activeNotifications.single { it.id == 1002 }.notification.actions).single().actionIntent
        fixture.home.setEnabled(false)
        await(10000) { !homeNotificationPresent() }
        fixture.home.setEnabled(true); awaitHome(fixture.home)
        val current = app.lastStartedEpoch
        assertTrue(current != old)
        val currentAction = requireNotNull(notifications.activeNotifications.single { it.id == 1002 }.notification.actions).single().actionIntent
        assertFalse(oldAction == currentAction)
        oldAction.send()
        app.startService(Intent(app, RouterHomeAutomationService::class.java).setAction(RouterHomeAutomationService.ACTION_STOP)
            .putExtra(RouterHomeAutomationService.EXTRA_REQUEST_EPOCH, old))
        ContextCompat.startForegroundService(app, Intent(app, RouterHomeAutomationService::class.java).setAction(RouterHomeAutomationService.ACTION_START)
            .putExtra(RouterHomeAutomationService.EXTRA_REQUEST_EPOCH, old))
        delay(2000)
        assertTrue(fixture.home.isEnabled); assertTrue(fixture.home.isCurrentRequest(current)); assertTrue(homeNotificationPresent())
    }

    @Test fun cancellationDuringRealEnrollmentCannotLeaveAForegroundRequestOrStartPhoneVpn() = withFixture { fixture ->
        coroutineScope {
            val previous = app.lastStartedEpoch
            val attempt = launch(start = CoroutineStart.UNDISPATCHED) { fixture.home.setEnabled(true) }
            attempt.cancelAndJoin()
            await(10000) { !fixture.home.isEnabled && !homeNotificationPresent() }
            assertEquals(previous, app.lastStartedEpoch)
            assertFalse(app.persistedHomeEnabled); assertFalse(app.controller.state.value.hasOwnedTunnel)
        }
    }

    @Test fun manualStartAndStopOverrideAutomaticPauseAndNeverReviveAnUnownedConnection() = withFixture(phone = true) { fixture ->
        fixture.connectPhone()
        fixture.home.setEnabled(true); awaitHome(fixture.home)
        awaitPaused()
        app.controller.connectSelected()
        awaitConnected()
        val manual = app.controller.routerManualGeneration.value
        val ticket = app.controller.state.value.connectionId
        delay(12000)
        assertTrue(app.controller.state.value.isConnected)
        assertEquals(ticket, app.controller.state.value.connectionId)
        assertEquals(manual, app.controller.routerManualGeneration.value)
        assertTrue(fixture.home.isEnabled)
        app.controller.disconnect(); awaitPaused()
        delay(10000)
        assertEquals(VpnPhase.IDLE, app.controller.state.value.phase)
        assertFalse(app.controller.state.value.hasOwnedTunnel)
        assertTrue(fixture.home.isEnabled)
    }

    @Test fun authenticatedRouterLossResumesOnlyThePausedOwnerAndRecoveryPausesItAgain() = withFixture(phone = true) { fixture ->
        fixture.connectPhone()
        val selected = app.controller.state.value.selectedProfileId
        val revision = app.controller.state.value.profileRevision
        fixture.home.setEnabled(true); awaitHome(fixture.home); awaitPaused()
        val server = requireNotNull(fixture.status().proof).serverId
        val manual = app.controller.routerManualGeneration.value
        try {
            fixture.running(false)
            awaitConnected()
            assertEquals(selected, app.controller.state.value.selectedProfileId)
            assertEquals(revision, app.controller.state.value.profileRevision)
            assertEquals(manual, app.controller.routerManualGeneration.value)
            assertNotNull(fixture.status().handoff)
            assertVpnBound204()
        } finally { fixture.running(true, server) }
        awaitPaused()
        assertEquals(manual, app.controller.routerManualGeneration.value)
        assertNotNull(fixture.status().proof)
        assertEquals(null, fixture.status().handoff)
    }

    @Test fun explicitOffKeepsTheNativeLeaseBeyondItsInitialExpiryAndOnAdoptsIt() = withFixture(phone = true) { fixture ->
        fixture.connectPhone()
        fixture.home.setEnabled(true); awaitHome(fixture.home); awaitPaused()
        fixture.home.setEnabled(false); awaitConnected()
        await(10000) { !homeNotificationPresent() }
        val owner = requireNotNull(app.leases.owner)
        val first = requireNotNull(fixture.status().handoff)
        marker("keeper-active", owner.connectionId)
        delay(65000)
        assertEquals(owner, app.leases.owner)
        assertTrue(app.controller.state.value.isConnected)
        assertFalse(fixture.home.isEnabled); assertFalse(homeNotificationPresent())
        val renewed = requireNotNull(fixture.status().handoff)
        assertEquals(first.claimId, renewed.claimId)
        assertTrue(renewed.expiresAtElapsedMs > first.expiresAtElapsedMs)
        assertVpnBound204()
        fixture.home.setEnabled(true); awaitHome(fixture.home); awaitPaused()
        assertEquals(null, app.leases.owner)
        assertEquals(null, fixture.status().handoff)
    }

    @Test fun rootDrivenWifiLossAndRejoinPreserveTheOwnedProfileAndManualGeneration() = withFixture(phone = true) { fixture ->
        assertEquals("enabled", arguments.getString("routerRootWifiToggle"))
        fixture.connectPhone()
        val selected = app.controller.state.value.selectedProfileId
        val revision = app.controller.state.value.profileRevision
        fixture.home.setEnabled(true); awaitHome(fixture.home); awaitPaused()
        val manual = app.controller.routerManualGeneration.value
        marker("await-wifi-off")
        await(30000) { !pairedWifiPresent() }
        await(10000) { app.controller.state.value.hasPendingStart || app.controller.state.value.hasOwnedTunnel }
        marker("await-wifi-on")
        await(30000) { pairedWifiPresent() }
        awaitPaused()
        assertEquals(selected, app.controller.state.value.selectedProfileId)
        assertEquals(revision, app.controller.state.value.profileRevision)
        assertEquals(manual, app.controller.routerManualGeneration.value)
        assertTrue(fixture.home.isEnabled); assertNotNull(fixture.status().proof)
        marker("wifi-rejoin-complete")
    }

    private class Fixture(val activity: ActivityScenario<RouterLabActivity>, val home: RouterHomeAutomationManager,
        val pair: RouterPairing, val transport: WifiRouterWizardTransport, val provisioner: RouterHttpProvisioner) {
        suspend fun status(): RouterPhoneStatus = transport.api(pair.identity).use { api ->
            api.unlockPairing(pair).use { session -> provisioner.phoneStatus(api, session, pair.id) }
        }
        suspend fun running(enabled: Boolean, server: String? = null) = transport.api(pair.identity).use { api ->
            api.unlockPairing(pair).use { session -> provisioner.setRunning(api, session, server, enabled) }
        }
        suspend fun connectPhone() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RouterLabApplication
            check(VpnService.prepare(app) == null) { "Root must grant VPN consent to the isolated lab package" }
            app.controller.connectSelected()
            withTimeout(60000) { app.controller.state.first { it.isConnected } }
        }
    }

    private fun withFixture(phone: Boolean = false, block: suspend (Fixture) -> Unit) = runBlocking {
        publicArgument("routerBackendGateSha256")
        requireRouterLocalNetworkAccess(app)
        check(routerHomeNotificationsAvailable(app)) { "Root must grant notification access to the isolated lab package" }
        app.controller.awaitReady()
        check(app.controller.state.value.profiles.isEmpty() && !app.controller.state.value.canDisconnect) { "Lab profile store must be idle and empty" }
        check(network.allNetworks.none { network.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) { "Owned acceptance must start without another VPN" }
        val activity = ActivityScenario.launch<RouterLabActivity>(Intent(app, RouterLabActivity::class.java))
        val protection = RouterSocketProtection(app.controller::protectRouterSocket)
        val transport = WifiRouterWizardTransport(network, socketProtection = protection, accessCheck = { requireRouterLocalNetworkAccess(app) })
        val identity = RouterTlsIdentity(RouterEndpoint(requireNotNull(arguments.getString("routerLanAddress")), arguments.getString("routerTlsPort")?.toInt() ?: 8443), publicArgument("routerCertificateSha256"))
        val provisioner = RouterHttpProvisioner(localAddress = { RouterWifiConnection.select(network, it.endpoint, protection) { requireRouterLocalNetworkAccess(app) }.localAddress.hostAddress!! }, pairingStore = app.store)
        val pair = app.store.read() ?: RouterPairing.create(identity, "RouterLab acceptance").also { app.store.save(it) }
        val imported = mutableListOf<String>()
        var home: RouterHomeAutomationManager? = null
        var primaryFailure: Throwable? = null
        try {
            check(pair.identity == identity && pair.name == "RouterLab acceptance") { "Lab pairing identity does not match this fixture" }
            transport.api(identity).use { api ->
                if (!pair.active) {
                    val pin = privateInput("panelPin").toCharArray()
                    api.unlock(pin).use { admin ->
                        check(!admin.changePinRequired)
                        api.createPairing(admin, pair)
                    }
                    pair.activated().let { active -> try { app.store.save(active) } finally { active.clear() } }
                }
                api.unlockPairing(pair).use { session ->
                    val capabilities = provisioner.capabilities(api, session)
                    check(capabilities.homeProof && capabilities.phoneHandoff) { "Genuine backend packet gates and fixture activation are required" }
                    provisioner.finish(api, session, pair, com.foxhole.vpn.router.RouterWizardOptions(countryCode = "NL", countrySitesDirect = false), null)
                    check(provisioner.phoneStatus(api, session, pair.id).proof != null) { "Fresh real router proof is required" }
                }
            }
            app.controller.updateSettings(app.controller.settings.value.copy(autoCheckConnectionData = false, autoRefreshSubscriptions = false))
            if (phone) {
                val result = app.controller.importProfiles(privateInput("phoneProfile"), allowInsecureTls = false)
                check(result is VpnImportResult.Success && result.rejected == 0 && result.added == 1)
                imported += app.controller.state.value.profiles.single().id
            }
            home = app.bindFixture()
            block(Fixture(activity, home, pair, transport, provisioner))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val failures = mutableListOf<String>()
            withContext(NonCancellable) {
                suspend fun attempt(label: String, timeoutMs: Long = 5000, action: suspend () -> Unit) {
                    try { withTimeout(timeoutMs) { action() } }
                    catch (_: Throwable) { failures += label }
                }
                attempt("home-off", 15000) { home?.setEnabled(false) }
                attempt("phone-disconnect") { app.controller.disconnect() }
                attempt("phone-idle", 15000) { app.controller.state.first { it.phase == VpnPhase.IDLE && !it.canDisconnect } }
                attempt("home-notification-absent", 10000) { await(10000) { !homeNotificationPresent() } }
                imported.forEach { id ->
                    attempt("owned-profile-removal") {
                        app.controller.removeProfile(id)
                        app.controller.state.first { state -> state.profiles.none { it.id == id } }
                    }
                }
                attempt("pairing-buffer-wipe") { pair.clear() }
                attempt("activity-close", 10000) { runInterruptible(Dispatchers.IO) { activity.close() } }
                attempt("cleanup-marker") {
                    runInterruptible(Dispatchers.IO) { marker(if (failures.isEmpty()) "case-cleanup-complete" else "case-cleanup-unknown") }
                }
            }
            if (failures.isNotEmpty()) {
                val cleanupFailure = AssertionError("Isolated lab cleanup unconfirmed: " + failures.joinToString(","))
                val original = primaryFailure
                if (original == null) throw cleanupFailure else original.addSuppressed(cleanupFailure)
            }
        }
    }

    private suspend fun awaitHome(home: RouterHomeAutomationManager) {
        await(10000) { home.isEnabled && home.status.value == RouterHomeMonitorState.MONITORING && homeNotificationPresent() }
    }
    private suspend fun awaitConnected() = withTimeout(60000) { app.controller.state.first { it.isConnected } }
    private suspend fun awaitPaused() = withTimeout(60000) { app.controller.state.first { it.phase == VpnPhase.IDLE && !it.canDisconnect } }
    private fun homeNotificationPresent() = notifications.activeNotifications.any { it.id == 1002 }
    private fun pairedWifiPresent() = try {
        RouterWifiConnection.select(network, RouterEndpoint(requireNotNull(arguments.getString("routerLanAddress"))), accessCheck = { requireRouterLocalNetworkAccess(app) }).requireCurrent()
        true
    } catch (_: Exception) { false }
    private suspend fun await(timeoutMs: Long, condition: () -> Boolean) = withTimeout(timeoutMs) { while (!condition()) delay(100) }
    private fun assertVpnBound204() {
        val vpn = network.allNetworks.single { network.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val connection = vpn.openConnection(URL("https://connectivitycheck.gstatic.com/generate_204"), Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000; connection.instanceFollowRedirects = false
            assertEquals(204, connection.responseCode)
        } finally { connection.disconnect() }
    }
    private fun privateInput(key: String): String {
        val file = File(app.noBackupFilesDir, "router-lab-acceptance/auth.json")
        check(file.isFile && file.length() in 1..65536) { "Private synthetic lab inputs missing" }
        val bytes = file.readBytes()
        return try {
            requireBoundedRouterJson(bytes, maxDepth = 8, maxNodes = 128)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("input missing")
        } catch (_: Exception) { throw AssertionError("Private synthetic lab input shape invalid") }
        finally { bytes.fill(0) }
    }
    private fun publicArgument(key: String): String = requireNotNull(arguments.getString(key)).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun marker(stage: String, ticket: Long? = null) {
        val file = File(app.noBackupFilesDir, "router-home-stage.json")
        file.writeText(buildJsonObject { put("stage", stage); put("elapsedMs", SystemClock.elapsedRealtime()); ticket?.let { put("ticket", it) } }.toString())
    }
}
