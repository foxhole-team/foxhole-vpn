package com.foxhole.vpn.flags

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FlagCodesTest {
    @Test fun supportedLanguageFlagsMatchTheExistingGuardMappings() {
        assertEquals(listOf("gb", "ru", "de", "fr", "es", "jp", "cn"),
            listOf("en", "ru", "de", "fr", "es", "ja", "zh-CN").map(::languageFlagCountry))
        assertNull(languageFlagCountry(""))
        assertNull(languageFlagCountry("it"))
    }

    @Test fun countryCodesCannotEscapeTheLocalFlagInventory() {
        assertEquals("de", flagCountryCode(" DE "))
        listOf(null, "", "d", "de.svg", "../de", "de/fr", "DE\\GB", "ＤＥ", "d1", "gb-sct").forEach {
            assertNull(flagCountryCode(it))
        }
    }

    @Test fun countryNormalizationDoesNotDependOnTheAppOrSystemLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("it", flagCountryCode("IT"))
            assertEquals("ir", flagCountryCode("IR"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun flagGeometryKeepsTheSourceAspectRatioAtTheCapitalHeight() {
        for (height in listOf(5f, 11f, 23f, 37f)) {
            val size = requireNotNull(countryFlagSizePx(height, 256, 192))
            assertEquals(height, size.height)
            assertEquals(4f / 3f, size.width / size.height, .0001f)
        }
    }

    @Test fun invalidFontOrImageGeometryCannotAllocateAFlag() {
        for (height in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertNull(countryFlagSizePx(height, 256, 192))
        }
        assertNull(countryFlagSizePx(11f, 0, 192))
        assertNull(countryFlagSizePx(11f, 256, -1))
        assertTrue(requireNotNull(countryFlagSizePx(11f, 256, 192)).width > 0f)
    }
}
