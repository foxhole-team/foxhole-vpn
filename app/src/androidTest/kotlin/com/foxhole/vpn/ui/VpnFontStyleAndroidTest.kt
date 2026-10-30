package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnFontStyle
import com.foxhole.vpn.VpnSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnFontStyleAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fontMenuMatchesItsAnchorAndBothChoicesFitAtCompactWidth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            var settings by remember { mutableStateOf(VpnSettings()) }
            VpnUiTheme(settings.theme, settings.fontStyle) {
                Box(Modifier.width(260.dp)) {
                    VpnSettingsDetailScreen(VpnSettingsDetail.THEME, settings,
                        onSettings = { settings = it }, onBack = {})
                }
            }
        }
        listOf(R.string.vpn_font_standard, R.string.vpn_font_pixel).forEach { choice ->
            val anchor = compose.onNodeWithTag("font-style-anchor")
            anchor.performClick()
            val button = anchor.fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithTag("font-style-menu").fetchSemanticsNode().boundsInRoot
            val option = compose.onNodeWithText(context.getString(choice)).assertIsDisplayed()
            val text = option.fetchSemanticsNode().boundsInRoot
            assertTrue(abs(button.width - menu.width) <= 2f)
            assertTrue(text.left >= menu.left && text.right <= menu.right)
            option.performClick()
        }
    }

    @Test fun fontDropdownChangesHeadingsWithoutLeavingThemeSettingsOrChangingBodyText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var current = VpnSettings()
        var backCalls = 0
        compose.setContent {
            var settings by remember { mutableStateOf(VpnSettings()) }
            current = settings
            VpnUiTheme(settings.theme, settings.fontStyle) {
                VpnSettingsDetailScreen(VpnSettingsDetail.THEME, settings,
                    onSettings = { settings = it }, onBack = { backCalls++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_font_style)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_font_pixel)).performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_font_standard)).performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_theme)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(VpnFontStyle.STANDARD, current.fontStyle)
            assertEquals(VpnSettings().theme, current.theme)
            assertEquals(0, backCalls)
            val pixel = vpnTypographyFor(VpnFontStyle.PIXEL)
            val standard = vpnTypographyFor(VpnFontStyle.STANDARD)
            assertNotEquals(pixel.titleLarge.fontFamily, standard.titleLarge.fontFamily)
            assertEquals(standard.bodyLarge.fontFamily, standard.titleLarge.fontFamily)
            assertEquals(standard.bodyLarge.fontFamily, standard.labelLarge.fontFamily)
            assertNotEquals(pixel.labelLarge.fontFamily, standard.labelLarge.fontFamily)
            assertEquals(pixel.bodyLarge, standard.bodyLarge)
        }
    }
}
