package com.foxhole.vpn.router.lab

import android.os.Bundle
import android.widget.TextView
import android.net.VpnService
import android.net.ConnectivityManager
import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.router.GithubRouterSetupRelease
import com.foxhole.vpn.router.LabVerifiedRouterBundle
import com.foxhole.vpn.router.RouterHttpProvisioner
import com.foxhole.vpn.router.RouterPhoneProfileExport
import com.foxhole.vpn.router.RouterSocketProtection
import com.foxhole.vpn.router.RouterWifiConnection
import com.foxhole.vpn.router.RouterWizardCoordinator
import com.foxhole.vpn.router.WifiRouterSetupService
import com.foxhole.vpn.router.WifiRouterWizardTransport
import com.foxhole.vpn.router.requireRouterLocalNetworkAccess
import com.foxhole.vpn.router.routerLocalNetworkAccessGranted

internal class RouterLabActivity : ComponentActivity() {
    internal var wizard: RouterWizardCoordinator? = null
        private set
    private var networkAction: (() -> Unit)? = null
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = networkAction; networkAction = null
        if (granted && routerLocalNetworkAccessGranted(this)) action?.invoke()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "FoxHole Router Lab\nIsolated foreground acceptance" })
    }
    override fun onResume() { super.onResume(); (application as RouterLabApplication).activityResumed() }
    override fun onPause() { (application as RouterLabApplication).activityPaused(); super.onPause() }
    @Suppress("DEPRECATION")
    internal fun requestVpnConsent() { VpnService.prepare(this)?.let { startActivityForResult(it, 9001) } }

    internal fun configureWizard(manifestSha256: String) {
        wizard?.close()
        val app = application as RouterLabApplication
        val network = requireNotNull(getSystemService(ConnectivityManager::class.java))
        val protection = RouterSocketProtection(app.controller::protectRouterSocket)
        val access = { requireRouterLocalNetworkAccess(this) }
        val bundle = LabVerifiedRouterBundle(this, manifestSha256)
        val release = GithubRouterSetupRelease("https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0", manifestSha256, bundle)
        val setup = WifiRouterSetupService.forWifi(network, release, protection, access, bundle::commandSource)
        val export = RouterPhoneProfileExport(app.controller)
        val actual = RouterWizardCoordinator(WifiRouterWizardTransport(network, socketProtection = protection, accessCheck = access),
            app.store, "RouterLab acceptance", setup,
            RouterHttpProvisioner(localAddress = { RouterWifiConnection.select(network, it.endpoint, protection, access).localAddress.hostAddress!! }, pairingStore = app.store),
            export::selected, { value ->
                val result = app.controller.importProfiles(value, allowInsecureTls = false)
                result is VpnImportResult.Success && result.rejected == 0 && result.added + result.updated > 0
            })
        wizard = actual
        setContent { LabRouterWizardHost(actual, app.controller, ::requestNetworkAction) }
    }

    private fun requestNetworkAction(action: () -> Unit) {
        if (routerLocalNetworkAccessGranted(this)) action()
        else if (Build.VERSION.SDK_INT >= 37) { networkAction = action; networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
    }
    override fun onDestroy() { networkAction = null; wizard?.close(); super.onDestroy() }
}
