package com.foxhole.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AppLanguagePolicyTest {
    private val system = VpnLanguage.SYSTEM
    private val choices = VPN_APP_LANGUAGE_CHOICES

    @Test fun `picker has seven languages with Japanese immediately before Chinese`() {
        assertEquals(listOf("SYSTEM", "EN", "RU", "DE", "FR", "ES", "JA", "ZH"), choices.map { it.name })
        assertEquals(8, choices.distinct().size)
        assertEquals("zh-CN", VpnLanguage.ZH.appLanguageTags())
        assertEquals(listOf("gb", "ru", "de", "fr", "es", "jp", "cn"),
            choices.filter { it != VpnLanguage.SYSTEM }.map(::vpnLanguageFlagCountry))
        assertNull(vpnLanguageFlagCountry(VpnLanguage.SYSTEM))
    }

    @Test fun `stored enum names and legacy order remain unchanged`() {
        assertEquals(listOf("SYSTEM", "EN", "RU", "JA", "DE", "FR", "ES", "ZH"), VpnLanguage.entries.map { it.name })
    }

    @Test fun `empty OS list does not erase an existing explicit choice on first migration`() {
        choices.filter { it != system }.forEach { saved ->
            assertEquals(AppLanguageDecision(saved, true), decideAppLanguage(saved, system, null, system))
        }
    }

    @Test fun `initial system choice stays system without setting a default locale`() {
        assertEquals(AppLanguageDecision(system, false), decideAppLanguage(system, system, null, system))
    }

    @Test fun `first existing OS selection is honored and mirrored`() {
        choices.forEach { saved ->
            choices.filter { it != system }.forEach { selected ->
                assertEquals(AppLanguageDecision(selected, false), decideAppLanguage(saved, selected, null, system))
            }
        }
    }

    @Test fun `completed migration honors later OS changes including system reset`() {
        choices.forEach { saved -> choices.forEach { selected ->
            assertEquals(AppLanguageDecision(selected, false), decideAppLanguage(saved, selected, saved, system))
        } }
    }

    @Test fun `durable app choice is retried after process death or failed platform apply`() {
        choices.forEach { previous -> choices.filter { it != previous }.forEach { saved ->
            choices.forEach { os ->
                assertEquals(AppLanguageDecision(saved, true), decideAppLanguage(saved, os, previous, system))
            }
            assertEquals(AppLanguageDecision(saved, true), decideAppLanguage(saved, null, previous, system))
        } }
    }

    @Test fun `unsupported platform locale never silently resets an existing selection`() {
        choices.forEach { saved ->
            assertNull(decideAppLanguage(saved, null, null, system))
            assertNull(decideAppLanguage(saved, null, saved, system))
        }
    }

    @Test fun `canonical platform tags round trip and legacy Chinese tag remains accepted`() {
        choices.forEach { language -> assertEquals(language, languageFromPlatformTag(language.appLanguageTags())) }
        assertEquals(VpnLanguage.ZH, languageFromPlatformTag("zh"))
        assertEquals(VpnLanguage.ZH, languageFromPlatformTag("zh-Hans-CN"))
        assertEquals(VpnLanguage.EN, languageFromPlatformTag("en-US"))
        assertNull(languageFromPlatformTag("zh-Hant-TW"))
        assertNull(languageFromPlatformTag("zh-HK"))
        assertNull(languageFromPlatformTag("it"))
    }

    @Test fun `all language selections survive settings serialization without changing other settings`() {
        choices.forEach { language ->
            val saved = VpnSettings(theme = VpnTheme.NIGHT, language = language,
                autoRefreshSubscriptions = false, autoCheckConnectionData = false)
            val raw = kotlinx.serialization.json.Json.encodeToString(VpnSettings.serializer(), saved)
            assertEquals(saved, kotlinx.serialization.json.Json.decodeFromString(VpnSettings.serializer(), raw))
        }
    }

    @Test fun `completed app checkpoint lets the next OS change win`() {
        val saved = VpnLanguage.DE
        val decision = decideAppLanguage(saved, VpnLanguage.FR, saved, system)!!
        assertEquals(VpnLanguage.FR, decision.language)
        assertFalse(decision.applyStored)
        assertTrue(decideAppLanguage(saved, system, VpnLanguage.RU, system)!!.applyStored)
    }
}
