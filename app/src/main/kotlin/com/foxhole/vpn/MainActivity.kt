package com.foxhole.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.foxhole.vpn.ui.LocalVpnColors
import com.foxhole.vpn.ui.VpnApp
import com.foxhole.vpn.ui.VpnAppStartup
import com.foxhole.vpn.ui.VpnUiActions
import com.foxhole.vpn.ui.VpnUiTheme
import com.foxhole.vpn.qr.QrScanActivity
import com.foxhole.vpn.appupdates.VpnAppInstallAction
import com.foxhole.vpn.appupdates.VpnAppUpdatePhase
import com.foxhole.vpn.appupdates.VpnAppUpdateNotifier
import com.foxhole.vpn.ui.appUpdateErrorResource
import com.foxhole.vpn.router.ROUTER_LOCAL_NETWORK_PERMISSION
import com.foxhole.vpn.router.routerLocalNetworkAccessGranted
import com.foxhole.vpn.router.routerHomeNotificationsAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class MainActivity : AppCompatActivity() {
    private val licenseConsent get() = (application as VpnApplication).licenseConsent
    private val updateNotice get() = (application as VpnApplication).updateNotice
    private val runtimeController get() = (application as VpnApplication).runtimeController
    private val controller: VpnController get() = runtimeController
    private val routerRuntime get() = (application as VpnApplication).routerRuntime
    private var fileText by mutableStateOf<String?>(null)
    private var uiError by mutableStateOf<String?>(null)
    private var notificationRequested = false
    private var permissionFlowActive = false
    private var appUpdateRequested by mutableStateOf(false)
    private var appInstallPermissionPending = false
    private var appInstallPreparing = false
    private var appInstallerPending = false
    private var routerPermissionAction: (() -> Unit)? = null
    private var routerHomePermissionAction: (() -> Unit)? = null
    private val routerHomeNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = routerHomePermissionAction
        routerHomePermissionAction = null
        if (licenseConsent.state.value.accepted && granted && routerLocalNetworkAccessGranted(this) && routerHomeNotificationsAvailable(this)) action?.invoke()
        else if (action != null) uiError = getString(R.string.vpn_router_home_notifications_permission)
    }
    private val routerLocalNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = routerPermissionAction
        routerPermissionAction = null
        if (licenseConsent.state.value.accepted && granted && routerLocalNetworkAccessGranted(this)) action?.invoke()
        else if (action != null) uiError = getString(R.string.vpn_router_error_local_network_permission)
    }
    private val appUpdater get() = (application as VpnApplication).appUpdater
    private val appInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        appInstallerPending = false
    }
    private val appInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requested = appInstallPermissionPending
        appInstallPermissionPending = false
        if (!licenseConsent.state.value.accepted) return@registerForActivityResult
        if (requested && packageManager.canRequestPackageInstalls()) prepareAppInstall()
        else if (requested) uiError = getString(R.string.vpn_app_update_install_permission)
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        permissionFlowActive = false
        if (!licenseConsent.state.value.accepted) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            controller.connectSelected()
        } else {
            uiError = getString(R.string.vpn_error_permission_required)
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnPermission()
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                fileText = withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openInputStream(uri)).use(::readBoundedProfileText)
                }
                uiError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                uiError = getString(R.string.vpn_import_invalid_file)
            }
        }
    }
    private val scanQr = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringExtra(QrScanActivity.EXTRA_PROFILE_TEXT)
            if (!text.isNullOrBlank() && text.length <= MAX_IMPORT_BYTES) {
                fileText = text
                uiError = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { synchronizeAppLanguage() }
    }

    private suspend fun synchronizeAppLanguage() {
        try { synchronizeVpnAppLanguage(this, controller) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("FoxHoleLocale", "Language sync failed: ${error.javaClass.simpleName}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRequested = savedInstanceState?.getBoolean("notification_requested") ?: false
        permissionFlowActive = savedInstanceState?.getBoolean("vpn_permission_pending") ?: false
        appInstallPermissionPending = savedInstanceState?.getBoolean("app_install_permission_pending") ?: false
        appInstallerPending = savedInstanceState?.getBoolean("app_installer_pending") ?: false
        appUpdateRequested = savedInstanceState?.getBoolean("app_update_requested") ?: false
        showWidgetPermissionError(intent)
        showAppUpdateIntent(intent)
        enableEdgeToEdge()
        lifecycleScope.launch { licenseConsent.load() }
        setContent {
            val license by licenseConsent.state.collectAsStateWithLifecycle()
            val notice by updateNotice.state.collectAsStateWithLifecycle()
            val state by controller.state.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            val dataUpdate by runtimeController.dataUpdater.state.collectAsStateWithLifecycle()
            val appUpdate by appUpdater.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.isReady) {
                if (state.isReady) synchronizeAppLanguage()
            }
            LaunchedEffect(license.accepted) {
                if (license.accepted) updateNotice.load()
            }
            VpnUiTheme(settings.theme, settings.fontStyle) {
                val colors = LocalVpnColors.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = colors.light
                        isAppearanceLightNavigationBars = colors.light
                    }
                }
                VpnAppStartup(license, notice,
                    onAcceptLicense = { confirmed -> lifecycleScope.launch { licenseConsent.accept(confirmed) } },
                    onAcknowledgeNotice = { lifecycleScope.launch { updateNotice.acknowledge() } },
                    onClose = ::finish,
                ) {
                    VpnApp(
                        controller = controller,
                        state = state,
                        settings = settings,
                        actions = VpnUiActions(
                            connect = ::requestConnect,
                            chooseFile = { openDocument.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                            systemSettings = ::openSystemVpnSettings,
                            consumeFile = { fileText = null },
                            consumeError = { uiError = null },
                            updateData = { runtimeController.updateData() },
                            chooseQr = { scanQr.launch(Intent(this, QrScanActivity::class.java)) },
                            checkAppUpdate = { lifecycleScope.launch { appUpdater.check() } },
                            appUpdateAction = ::performAppUpdateAction,
                            consumeAppUpdate = { appUpdateRequested = false },
                        ),
                        importedFileText = fileText,
                        externalError = uiError,
                        dataUpdate = dataUpdate,
                        appUpdate = appUpdate,
                        openAppUpdate = appUpdateRequested,
                        onRouterEntryRequest = ::requestRouterAccess,
                        onRouterHomeEnableRequest = ::requestRouterHomeAccess,
                        routerWizard = routerRuntime.wizard,
                        routerProfileProbe = routerRuntime.profileProbe,
                        routerControl = routerRuntime.control,
                        exportRouterProfile = com.foxhole.vpn.router.RouterPhoneProfileExport(controller)::selected,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notification_requested", notificationRequested)
        outState.putBoolean("vpn_permission_pending", permissionFlowActive)
        outState.putBoolean("app_install_permission_pending", appInstallPermissionPending)
        outState.putBoolean("app_installer_pending", appInstallerPending)
        outState.putBoolean("app_update_requested", appUpdateRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showWidgetPermissionError(intent)
        showAppUpdateIntent(intent)
    }

    private fun showAppUpdateIntent(intent: Intent?) {
        if (intent?.action == VpnAppUpdateNotifier.ACTION_OPEN_APP_UPDATE) {
            appUpdateRequested = true
            setIntent(Intent(intent).setAction(null))
        }
    }

    private fun performAppUpdateAction() {
        if (!licenseConsent.state.value.accepted) return
        val update = appUpdater.state.value
        if (update.isBusy || appInstallPermissionPending || appInstallPreparing || appInstallerPending) return
        if (update.phase == VpnAppUpdatePhase.READY) prepareAppInstall()
        else lifecycleScope.launch {
            if (update.release != null) appUpdater.download() else appUpdater.check()
        }
    }

    private fun prepareAppInstall() {
        if (!licenseConsent.state.value.accepted) return
        if (appInstallPreparing || appInstallerPending) return
        appInstallPreparing = true
        lifecycleScope.launch {
            try {
                when (val action = appUpdater.prepareInstall(this@MainActivity)) {
                    is VpnAppInstallAction.Install -> {
                        appInstallerPending = true
                        try { appInstaller.launch(action.intent) }
                        catch (_: Exception) { appInstallerPending = false; uiError = getString(R.string.vpn_app_update_error_install) }
                    }
                    is VpnAppInstallAction.PermissionRequired -> {
                        appInstallPermissionPending = true
                        try { appInstallPermission.launch(action.intent) }
                        catch (_: Exception) { appInstallPermissionPending = false; uiError = getString(R.string.vpn_app_update_error_install) }
                    }
                    is VpnAppInstallAction.Failed -> uiError = getString(appUpdateErrorResource(action.error))
                    VpnAppInstallAction.Unavailable -> Unit
                }
            } finally { appInstallPreparing = false }
        }
    }

    private fun showWidgetPermissionError(intent: Intent?) {
        if (intent?.action == ACTION_WIDGET_PERMISSION_DENIED) uiError = getString(R.string.widget_permission_denied)
    }

    override fun onStart() {
        super.onStart()
        (application as VpnApplication).runtimeController.foregroundChanged(this, true)
    }

    override fun onStop() {
        (application as VpnApplication).runtimeController.foregroundChanged(this, false)
        super.onStop()
    }

    private fun requestConnect() {
        if (!licenseConsent.state.value.accepted) return
        uiError = null
        if (!controller.state.value.isReady || permissionFlowActive) return
        permissionFlowActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnPermission()
        }
    }

    private fun requestRouterAccess(action: () -> Unit) {
        if (!licenseConsent.state.value.accepted) return
        if (routerPermissionAction != null) return
        if (routerLocalNetworkAccessGranted(this)) {
            action()
            return
        }
        routerPermissionAction = action
        try { routerLocalNetworkPermission.launch(ROUTER_LOCAL_NETWORK_PERMISSION) }
        catch (_: Exception) {
            routerPermissionAction = null
            uiError = getString(R.string.vpn_router_error_local_network_permission)
        }
    }

    private fun requestRouterHomeAccess(action: () -> Unit) {
        requestRouterAccess {
            if (routerHomePermissionAction != null) return@requestRouterAccess
            if (routerHomeNotificationsAvailable(this)) action()
            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                routerHomePermissionAction = action
                try { routerHomeNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                catch (_: Exception) {
                    routerHomePermissionAction = null
                    uiError = getString(R.string.vpn_router_home_notifications_permission)
                }
            } else uiError = getString(R.string.vpn_router_home_notifications_permission)
        }
    }

    override fun onDestroy() {
        routerPermissionAction = null
        routerHomePermissionAction = null
        routerRuntime.close()
        super.onDestroy()
    }

    private fun requestVpnPermission() {
        if (!licenseConsent.state.value.accepted) {
            permissionFlowActive = false
            return
        }
        try {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                permissionFlowActive = false
                controller.connectSelected()
            } else {
                vpnPermission.launch(intent)
            }
        } catch (_: Exception) {
            permissionFlowActive = false
            uiError = getString(R.string.vpn_error_permission_required)
        }
    }

    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
            uiError = getString(R.string.vpn_error_action_failed)
        }
    }

    companion object {
        internal const val ACTION_WIDGET_PERMISSION_DENIED = "com.foxhole.vpn.WIDGET_PERMISSION_DENIED"
    }
}

internal fun readBoundedProfileText(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_IMPORT_BYTES) { "Profile input is too large" }
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
}

private const val MAX_IMPORT_BYTES = 1024 * 1024
