package com.foxhole.vpn.ui

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnFontStyle
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLanguageFlagsAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pickerUsesCountryImagesAndKeepsLanguageSelectionIndependentOfOtherSettings() {
        val initial = VpnSettings(theme = VpnTheme.NIGHT, language = VpnLanguage.EN, autoRefreshSubscriptions = false)
        var selected = initial
        var backCalls = 0
        compose.setContent {
            VpnUiTheme(initial.theme, initial.fontStyle) {
                VpnSettingsDetailScreen(VpnSettingsDetail.LANGUAGE, initial,
                    onSettings = { selected = it }, onBack = { backCalls++ })
            }
        }
        listOf("GB", "RU", "DE", "FR", "ES", "JP", "CN").forEach { code ->
            compose.onNodeWithContentDescription(code).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.vpn_language_japanese)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(initial.copy(language = VpnLanguage.JA), selected)
            assertEquals(1, backCalls)
        }
    }

    @Test fun selectedLanguageSummaryAlsoUsesTheFlagImage() {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnSettingsScreen(VpnSettings(language = VpnLanguage.EN),
                    onSettingsDetail = {}, onProfiles = {}, onSystemSettings = {})
            }
        }
        compose.onNodeWithContentDescription("GB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_language_english)).assertIsDisplayed()
    }

    @Test fun vectorHeightMatchesTheActualCapitalGlyphAcrossFontsStylesAndFontScale() {
        val font = mutableStateOf(VpnFontStyle.PIXEL)
        val scale = mutableStateOf(1f)
        val styleIndex = mutableStateOf(0)
        var expectedHeight = 0f
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK, font.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    val style = when (styleIndex.value) {
                        0 -> MaterialTheme.typography.bodySmall
                        1 -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.labelLarge
                    }
                    val bounds = Rect()
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = ResourcesCompat.getFont(context,
                            if (styleIndex.value == 2 && font.value == VpnFontStyle.PIXEL) R.font.tiny5_regular
                            else R.font.jetbrains_mono_bold)
                        textSize = with(LocalDensity.current) { style.fontSize.toPx() }
                        getTextBounds("H", 0, 1, bounds)
                    }
                    expectedHeight = bounds.height().toFloat()
                    Row {
                        VpnFlagIcon("GB", style)
                        Text("H", style = style)
                    }
                }
            }
        }
        for (selectedFont in VpnFontStyle.entries) for (fontScale in listOf(1f, 1.6f)) for (index in 0..2) {
            compose.runOnIdle {
                font.value = selectedFont
                scale.value = fontScale
                styleIndex.value = index
            }
            val flag = compose.onNodeWithContentDescription("GB").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertEquals(expectedHeight, flag.height, 1f)
                assertEquals(flag.height * 4f / 3f, flag.width, 1f)
            }
        }
    }
}
