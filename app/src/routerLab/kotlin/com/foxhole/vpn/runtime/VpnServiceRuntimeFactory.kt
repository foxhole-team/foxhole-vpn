package com.foxhole.vpn.runtime

import android.app.Application
import com.foxhole.vpn.router.lab.RouterLabApplication

internal fun vpnServiceRuntime(application: Application): VpnServiceRuntimeBinding =
    (application as RouterLabApplication).binding
