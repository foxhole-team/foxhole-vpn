package com.foxhole.vpn

import android.app.Application
import com.foxhole.vpn.widget.startWidgetUpdates
import com.foxhole.vpn.appupdates.VpnAppUpdater
import com.foxhole.vpn.appupdates.VpnAppUpdateWork
import com.foxhole.vpn.router.createRouterRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpnApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal lateinit var runtimeController: DefaultVpnController
        private set
    val controller: VpnController get() = runtimeController
    internal val licenseConsent by lazy { VpnLicenseConsent(VpnLicenseFileStore(this)) }
    internal val updateNotice by lazy { VpnUpdateNotice(VpnUpdateNoticeFileStore(this)) }
    internal val routerRuntime by lazy { createRouterRuntime(this, runtimeController, applicationScope) }
    internal lateinit var appUpdater: VpnAppUpdater
        private set

    override fun onCreate() {
        super.onCreate()
        runtimeController = DefaultVpnController(this, applicationScope) { licenseConsent.state.value.accepted }
        applicationScope.launch { licenseConsent.load() }
        routerRuntime // Origin-gated one-shot checks also cover a marked profile connected from the widget.
        appUpdater = VpnAppUpdater.create(this)
        applicationScope.launch { appUpdater.restore() }
        VpnAppUpdateWork.schedule(this)
        startWidgetUpdates(this, applicationScope)
    }
}
