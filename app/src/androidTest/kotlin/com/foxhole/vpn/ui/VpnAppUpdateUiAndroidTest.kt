package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.appupdates.VpnAppRelease
import com.foxhole.vpn.appupdates.VpnAppUpdateManifest
import com.foxhole.vpn.appupdates.VpnAppUpdatePhase
import com.foxhole.vpn.appupdates.VpnAppUpdateState
import com.foxhole.vpn.updates.VpnDataUpdatePhase
import com.foxhole.vpn.updates.VpnDataUpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnAppUpdateUiAndroidTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun homeKeepsApplicationAndNetworkDataActionsSeparate() {
        var app = 0
        var data = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnHome(VpnUiState(isReady = true), onPrimary = {}, onProfiles = {}, modifier = Modifier.fillMaxSize(),
                    dataUpdate = VpnDataUpdateState(VpnDataUpdatePhase.AVAILABLE), onDataUpdate = { data++ },
                    appUpdate = VpnAppUpdateState(VpnAppUpdatePhase.AVAILABLE, release), onAppUpdate = { app++ })
            }
        }
        compose.onNodeWithText(text(R.string.vpn_app_update_title)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, app); assertEquals(0, data) }
        compose.onNodeWithText(text(R.string.vpn_data_update_available)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, app); assertEquals(1, data) }
    }

    @Test fun applicationDownloadKeepsOwnedTunnelStopAvailable() {
        var stops = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.LIGHT) {
                VpnHome(VpnUiState(phase = VpnPhase.ERROR, hasOwnedTunnel = true), onPrimary = { stops++ }, onProfiles = {},
                    modifier = Modifier.fillMaxSize(),
                    appUpdate = VpnAppUpdateState(VpnAppUpdatePhase.DOWNLOADING, release, 37, 100),
                    onAppUpdate = { throw AssertionError("Duplicate download") })
            }
        }
        compose.onNodeWithText(text(R.string.vpn_app_update_title)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.vpn_stop)).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun externalDistributionExplainsWhySelfUpdateIsDisabled() {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnAppUpdateSheet(VpnAppUpdateState(VpnAppUpdatePhase.DISABLED),
                    onAction = { throw AssertionError("External update") }, onCheck = { throw AssertionError("External check") })
            }
        }
        compose.onNodeWithText(text(R.string.vpn_app_update_external_source)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_app_update_check)).assertIsNotEnabled()
    }

    private val release = VpnAppRelease(VpnAppUpdateManifest(100, "1.0.0", "foxhole-vpn.apk", "a".repeat(64)),
        "https://github.com/foxhole-team/foxhole-vpn/releases/download/v1.0.0/foxhole-vpn.apk", 100,
        "https://github.com/foxhole-team/foxhole-vpn/releases/tag/v1.0.0")
}
