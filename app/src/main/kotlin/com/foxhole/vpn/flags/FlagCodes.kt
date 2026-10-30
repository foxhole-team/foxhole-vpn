package com.foxhole.vpn.flags

import java.util.Locale

internal fun flagCountryCode(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { it.length == 2 && it.all { character -> character in 'a'..'z' } }

internal fun languageFlagCountry(languageTag: String): String? = when (languageTag) {
    "en" -> "gb"
    "ru" -> "ru"
    "de" -> "de"
    "fr" -> "fr"
    "es" -> "es"
    "ja" -> "jp"
    "zh-CN" -> "cn"
    else -> null
}
