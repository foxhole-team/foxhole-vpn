package com.foxhole.vpn.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class VpnBackgroundUpdateSettingsAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun settingsEntryOpensBackgroundUpdateControls() {
        var opened: VpnSettingsDetail? = null
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnSettingsScreen(VpnSettings(), onSettingsDetail = { opened = it },
                    onProfiles = {}, onSystemSettings = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_background_updates)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(VpnSettingsDetail.BACKGROUND_UPDATES, opened) }
    }

    @Test fun switchesEmitIndependentPersistableSettingsWithoutChangingIntervals() {
        val initial = VpnSettings(subscriptionRefreshIntervalHours = 36, connectionDataCheckIntervalHours = 48)
        val settings = mutableStateOf(initial)
        var changes = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnSettingsDetailScreen(VpnSettingsDetail.BACKGROUND_UPDATES, settings.value,
                    onSettings = { settings.value = it; changes++ }, onBack = {})
            }
        }
        val subscriptions = compose.onNodeWithContentDescription(context.getString(R.string.vpn_auto_refresh_subscriptions))
        val connectionData = compose.onNodeWithContentDescription(context.getString(R.string.vpn_auto_check_connection_data))
        subscriptions.assertIsOn().performClick().assertIsOff()
        connectionData.assertIsOn()
        compose.runOnIdle { assertEquals(initial.copy(autoRefreshSubscriptions = false), settings.value) }
        connectionData.performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(initial.copy(autoRefreshSubscriptions = false, autoCheckConnectionData = false), settings.value)
            assertEquals(2, changes)
        }
    }

    @Test fun disabledSettingsPreventBackgroundPolicyChanges() {
        var changes = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnSettingsDetailScreen(VpnSettingsDetail.BACKGROUND_UPDATES, VpnSettings(),
                    onSettings = { changes++ }, onBack = {}, enabled = false)
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_auto_refresh_subscriptions)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_auto_check_connection_data)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, changes) }
    }
}
