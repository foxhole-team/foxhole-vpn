package com.foxhole.vpn

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

internal fun Context.withVpnLocale(language: VpnLanguage): Context {
    if (language == VpnLanguage.SYSTEM) return this
    val locale = Locale.forLanguageTag(when (language) {
        VpnLanguage.SYSTEM -> ""
        VpnLanguage.EN -> "en"
        VpnLanguage.RU -> "ru"
        VpnLanguage.JA -> "ja"
        VpnLanguage.DE -> "de"
        VpnLanguage.FR -> "fr"
        VpnLanguage.ES -> "es"
        VpnLanguage.ZH -> "zh-CN"
    })
    val configuration = Configuration(resources.configuration).apply { setLocales(LocaleList(locale)) }
    return createConfigurationContext(configuration)
}
