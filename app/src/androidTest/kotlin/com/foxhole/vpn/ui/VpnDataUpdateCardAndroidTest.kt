package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.updates.VpnDataUpdateState
import com.foxhole.vpn.updates.VpnDataUpdatePhase
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnDataUpdateCardAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun availableDataUpdateInvokesOnlyItsDownloadAction() {
        var clicks = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnDataUpdateCard("Data update available", "New connection data", null, false, { clicks++ }, Modifier.fillMaxWidth())
            }
        }
        compose.onNodeWithText("Data update available").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun downloadingCardReportsMeasuredProgressAndDisablesDuplicateActions() {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnDataUpdateCard("Downloading data", "37%", .37f, true, { throw AssertionError("Duplicate download") })
            }
        }
        compose.onNodeWithText("Downloading data").assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.37f, 0f..1f)),
        )
    }

    @Test
    fun homePlacesOvalProfileBelowGlobeAndDataUpdateBelowStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnHome(
                    VpnUiState(isReady = true, profiles = listOf(VpnProfileSummary("owned", "Owned profile", "VLESS")), selectedProfileId = "owned"),
                    onPrimary = { throw AssertionError("Unexpected VPN command") },
                    onProfiles = {}, modifier = Modifier.fillMaxSize(),
                    dataUpdate = VpnDataUpdateState(VpnDataUpdatePhase.AVAILABLE),
                )
            }
        }
        val globe = compose.onNodeWithContentDescription(context.getString(R.string.vpn_globe)).fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText(context.getString(R.string.vpn_status_idle)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val profile = compose.onNodeWithText("Owned profile").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithText(context.getString(R.string.vpn_start)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val update = compose.onNodeWithText(context.getString(R.string.vpn_data_update_available)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(globe.bottom <= status.top)
        assertTrue(status.bottom <= profile.top)
        assertTrue(profile.bottom <= start.top)
        assertTrue(start.bottom <= update.top)
    }

    @Test
    fun dataDownloadNeverDisablesStopForAnOwnedTunnel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var stops = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnHome(
                    VpnUiState(phase = VpnPhase.ERROR, hasOwnedTunnel = true),
                    onPrimary = { stops++ }, onProfiles = {}, modifier = Modifier.fillMaxSize(),
                    dataUpdate = VpnDataUpdateState(VpnDataUpdatePhase.DOWNLOADING, 37, 100),
                    onDataUpdate = { throw AssertionError("Duplicate download") },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.vpn_data_update_downloading)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.vpn_stop)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun unknownDownloadLengthReportsIndeterminateProgress() {
        compose.setContent {
            VpnUiTheme(VpnTheme.LIGHT) {
                VpnDataUpdateCard("Verifying data", "Checking signature", null, true, { throw AssertionError("Duplicate action") })
            }
        }
        compose.onNodeWithText("Verifying data").assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
    }
}
