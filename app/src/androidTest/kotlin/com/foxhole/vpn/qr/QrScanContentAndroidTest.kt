package com.foxhole.vpn.qr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.ui.VpnModalTheme
import com.foxhole.vpn.ui.VpnUiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrScanContentAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun denialWaitsForExplicitPermissionSettingsOrBackActions() {
        var permissions = 0
        var settings = 0
        var backs = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) { VpnModalTheme {
                QrScanContent(QrScanState(), { permissions++ }, { settings++ }, {}, { backs++ }) {
                    error("preview must not be created without permission")
                }
            } }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_qr_camera_permission_body)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, permissions) }
        compose.onNodeWithText(context.getString(R.string.vpn_qr_allow_camera)).performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_qr_open_settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_qr_back)).performClick()
        compose.runOnIdle { assertEquals(1, permissions); assertEquals(1, settings); assertEquals(1, backs) }
    }

    @Test fun cameraFailureOffersAnExplicitRetryAndBack() {
        var retries = 0
        var backs = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.LIGHT) { VpnModalTheme {
                QrScanContent(QrScanState(permissionGranted = true, cameraUnavailable = true), {}, {}, { retries++ }, { backs++ }) {
                    error("failed camera must not create a preview")
                }
            } }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_qr_camera_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_qr_retry)).performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_qr_back)).performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, backs) }
    }
}
