package com.foxhole.vpn.runtime

import android.app.Application
import com.foxhole.vpn.review.ReviewApplication

internal fun vpnServiceRuntime(application: Application): VpnServiceRuntimeBinding {
    check(application is ReviewApplication)
    error("VPN services are unavailable in the offline router review")
}
