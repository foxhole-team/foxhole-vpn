package com.foxhole.vpn.ui

import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.languageFromPlatformTag
import com.foxhole.vpn.vpnLanguageFlagCountry

internal fun displayedLanguageFlagCountry(language: VpnLanguage, effectiveTag: String): String? {
    val displayed = if (language == VpnLanguage.SYSTEM) languageFromPlatformTag(effectiveTag) ?: return null else language
    return vpnLanguageFlagCountry(displayed)
}

