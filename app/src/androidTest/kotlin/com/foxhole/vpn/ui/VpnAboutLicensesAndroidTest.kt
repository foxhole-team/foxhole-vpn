package com.foxhole.vpn.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnAboutLicensesAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aboutEntryOpensComponentLicenses() {
        var opened: VpnSettingsDetail? = null
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnSettingsScreen(VpnSettings(), onSettingsDetail = { opened = it },
                    onProfiles = {}, onSystemSettings = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_about)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(VpnSettingsDetail.ABOUT, opened) }
    }

    @Test fun aboutKeepsVersionAndSoftwareLicensesWithoutTheEarthFooterAtCompactWidth() {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                Box(Modifier.width(260.dp)) {
                    VpnSettingsDetailScreen(VpnSettingsDetail.ABOUT, VpnSettings(), onSettings = {}, onBack = {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_about_text, com.foxhole.vpn.BuildConfig.VERSION_NAME))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_licenses_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("FoxHole Core").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("JSch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("flag-icons").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Natural Earth").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.vpn_license_public_domain)).assertDoesNotExist()
    }

    @Test fun readingLicensesDoesNotChangeSettingsOrLeaveAbout() {
        val settings = VpnSettings()
        var changed = 0
        var backed = 0
        compose.setContent {
            VpnUiTheme(settings.theme) {
                VpnSettingsDetailScreen(VpnSettingsDetail.ABOUT, settings,
                    onSettings = { changed++ }, onBack = { backed++ })
            }
        }
        compose.onNodeWithText("flag-icons").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, changed)
            assertEquals(0, backed)
        }
    }
}
