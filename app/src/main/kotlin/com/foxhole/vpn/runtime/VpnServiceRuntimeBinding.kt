package com.foxhole.vpn.runtime

import android.app.Activity
import com.foxhole.vpn.DefaultVpnController
import com.foxhole.vpn.router.RouterHomeAutomationManager

internal interface VpnServiceRuntimeBinding {
    val controller: DefaultVpnController
    val home: RouterHomeAutomationManager
    val activity: Class<out Activity>
}
