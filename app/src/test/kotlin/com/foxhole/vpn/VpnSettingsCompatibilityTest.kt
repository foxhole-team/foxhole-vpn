package com.foxhole.vpn

import com.foxhole.vpn.storage.ProfileStoreState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnSettingsCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Test
    fun vaultPayloadWithoutGlobePreferencePreservesItsProfilesAndSettings() {
        val previous = """{
            "schema":1,
            "profiles":[{"id":"fixture","name":"Example","protocol":"VLESS","normalizedConfigJson":"synthetic-profile"}],
            "selectedProfileId":"fixture",
            "settings":{"theme":"DARK","language":"RU"}
        }"""
        val state = json.decodeFromString<ProfileStoreState>(previous)
        assertEquals("fixture", state.selectedProfileId)
        assertEquals("synthetic-profile", state.profiles.single().normalizedConfigJson)
        assertEquals(VpnTheme.DARK, state.settings.theme)
        assertEquals(VpnLanguage.RU, state.settings.language)
        assertEquals(VpnGlobeStyle.VOLUME, state.settings.globeStyle)
        assertEquals(VpnFontStyle.PIXEL, state.settings.fontStyle)
        assertEquals(true, state.settings.autoRefreshSubscriptions)
        assertEquals(12, state.settings.subscriptionRefreshIntervalHours)
        assertEquals(true, state.settings.autoCheckConnectionData)
        assertEquals(24, state.settings.connectionDataCheckIntervalHours)
        assertEquals(0, state.automaticRefresh.subscriptions.size)
    }

    @Test
    fun unrelatedPreferenceChangesKeepThePersistedGlobeSelection() {
        val saved = VpnSettings(globeStyle = VpnGlobeStyle.VECTOR)
        val changed = saved.copy(language = VpnLanguage.RU)
        val reloaded = json.decodeFromString<VpnSettings>(json.encodeToString(changed))
        assertEquals(VpnGlobeStyle.VECTOR, reloaded.globeStyle)
        assertEquals(VpnLanguage.RU, reloaded.language)
    }

    @Test
    fun freshAndMissingThemePreferencesKeepSystemDefault() {
        assertEquals(VpnTheme.SYSTEM, VpnSettings().theme)
        assertEquals(VpnTheme.SYSTEM, json.decodeFromString<VpnSettings>("{}").theme)
    }

    @Test
    fun existingThemeTokensAreNotMigratedToNight() {
        for (theme in listOf(VpnTheme.SYSTEM, VpnTheme.DARK, VpnTheme.LIGHT)) {
            val reloaded = json.decodeFromString<VpnSettings>("""{"theme":"${theme.name}"}""")
            assertEquals(theme, reloaded.theme)
            assertEquals(theme, json.decodeFromString<VpnSettings>(json.encodeToString(reloaded)).theme)
        }
    }

    @Test
    fun explicitNightSelectionSurvivesAnUnrelatedPreferenceChange() {
        val saved = VpnSettings(theme = VpnTheme.NIGHT, language = VpnLanguage.EN)
        val reloaded = json.decodeFromString<VpnSettings>(json.encodeToString(saved.copy(language = VpnLanguage.RU)))
        assertEquals(VpnTheme.NIGHT, reloaded.theme)
        assertEquals(VpnLanguage.RU, reloaded.language)
    }

    @Test
    fun standardHeadingsPersistWithoutChangingThemeOrRefreshPreferences() {
        val saved = VpnSettings(theme = VpnTheme.NIGHT, fontStyle = VpnFontStyle.STANDARD)
        val reloaded = json.decodeFromString<VpnSettings>(json.encodeToString(saved.copy(language = VpnLanguage.RU)))
        assertEquals(VpnFontStyle.STANDARD, reloaded.fontStyle)
        assertEquals(VpnTheme.NIGHT, reloaded.theme)
        assertEquals(12, reloaded.subscriptionRefreshIntervalHours)
        assertEquals(VpnFontStyle.PIXEL, json.decodeFromString<VpnSettings>("{}").fontStyle)
    }
}
