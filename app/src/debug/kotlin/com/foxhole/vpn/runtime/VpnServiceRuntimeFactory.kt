package com.foxhole.vpn.runtime

import android.app.Application
import com.foxhole.vpn.MainActivity
import com.foxhole.vpn.VpnApplication

internal fun vpnServiceRuntime(application: Application): VpnServiceRuntimeBinding {
    val app = application as VpnApplication
    return object : VpnServiceRuntimeBinding {
        override val controller get() = app.runtimeController
        override val home get() = app.routerRuntime.home
        override val activity get() = MainActivity::class.java
    }
}
