package com.foxhole.vpn


internal val VPN_APP_LANGUAGE_CHOICES = listOf(VpnLanguage.SYSTEM, VpnLanguage.EN, VpnLanguage.RU, VpnLanguage.DE, VpnLanguage.FR, VpnLanguage.ES, VpnLanguage.JA, VpnLanguage.ZH)

internal data class AppLanguageDecision<T>(val language: T, val applyStored: Boolean)

internal fun <T> decideAppLanguage(stored: T, platform: T?, lastSynced: T?, system: T): AppLanguageDecision<T>? {
    // Encrypted choice changes since the completed sync are durable app intent.
    if (lastSynced != null && stored != lastSynced) return AppLanguageDecision(stored, true)
    if (platform == null) return null
    val keepStored = lastSynced == null && platform == system && stored != system
    return AppLanguageDecision(if (keepStored) stored else platform, keepStored)
}

internal fun languageFromPlatformTag(tag: String): VpnLanguage? {
    if (tag.isEmpty()) return VpnLanguage.SYSTEM
    val locale = java.util.Locale.forLanguageTag(tag)
    return when (locale.language) {
        "en" -> VpnLanguage.EN
        "ru" -> VpnLanguage.RU
        "de" -> VpnLanguage.DE
        "fr" -> VpnLanguage.FR
        "es" -> VpnLanguage.ES
        "ja" -> VpnLanguage.JA
        "zh" -> VpnLanguage.ZH.takeIf { locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO") }
        else -> null
    }
}

internal fun VpnLanguage.appLanguageTags(): String = when (this) {
    VpnLanguage.SYSTEM -> ""
    VpnLanguage.EN -> "en"
    VpnLanguage.RU -> "ru"
    VpnLanguage.DE -> "de"
    VpnLanguage.FR -> "fr"
    VpnLanguage.ES -> "es"
    VpnLanguage.JA -> "ja"
    VpnLanguage.ZH -> "zh-CN"
}

internal fun vpnLanguageFlagCountry(language: VpnLanguage): String? =
    com.foxhole.vpn.flags.languageFlagCountry(language.appLanguageTags())
