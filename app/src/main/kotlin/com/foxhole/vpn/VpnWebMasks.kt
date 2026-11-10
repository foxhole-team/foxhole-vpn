package com.foxhole.vpn

import com.foxhole.vpn.engine.normalizeVpnWebsiteMask

internal fun normalizeVpnWebMask(value: String): String? = normalizeVpnWebsiteMask(value)
