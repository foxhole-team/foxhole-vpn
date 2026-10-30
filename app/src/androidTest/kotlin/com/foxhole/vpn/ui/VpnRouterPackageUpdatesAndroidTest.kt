package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.router.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnRouterPackageUpdatesAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unsupportedNeverSaysUpToDateOrOffersAnUpdate() {
        compose.setContent { VpnUiTheme(VpnTheme.DARK) { Column {
            VpnRouterPackageUpdates(RouterUpdateControlState(supported = false), true, {})
            VpnRouterPackageUpdateFooter(RouterUpdateControlState(supported = false), true, {}, {}, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.router_updates_unsupported)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_updates_current)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.router_updates_apply)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.router_updates_check)).assertIsNotEnabled()
    }

    @Test fun uncertainOutcomeOffersOnlyStatusAndNeverApplyOrAnotherCheck() {
        var refresh = 0
        val state = RouterUpdateControlState(supported = true,
            pending = RouterUpdatePending(RouterUpdateOperation.APPLY, "update-plan-" + "a".repeat(32), null))
        compose.setContent { VpnUiTheme(VpnTheme.NIGHT) { Column {
            VpnRouterPackageUpdates(state, true, {})
            VpnRouterPackageUpdateFooter(state, true, { error("must not check") }, { error("must not apply") }, { refresh++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.router_updates_uncertain)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_updates_apply)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.router_updates_check)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.router_updates_status)).performClick()
        compose.runOnIdle { assertEquals(1, refresh) }
    }

    @Test fun autoCheckboxSendsOneRequestAndWaitsForAuthoritativeReadback() {
        var requests = 0
        val status = RouterUpdateStatus(true, "a".repeat(64), 1, "1.0.0-r2", null, RouterUpdateAvailability.NOT_CHECKED,
            null, null, false, RouterUpdateAuto(false, 86400, null, null, null), emptyList(), null, null, null)
        compose.setContent { VpnUiTheme(VpnTheme.LIGHT) { Column {
            VpnRouterPackageUpdates(RouterUpdateControlState(true, status), true, { requests++ })
        } } }
        compose.onNodeWithText(context.getString(R.string.router_updates_auto)).performClick().assertIsOff()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun legacyConnectionShowsDisconnectGuidanceWithoutCallingThePlatformUnsupported() {
        val status = RouterUpdateStatus(true, "a".repeat(64), 1, "1.0.0-r2", "1.0.1-r1", RouterUpdateAvailability.AVAILABLE,
            null, null, false, RouterUpdateAuto(false, 86400, null, null, RouterUpdateFailureReason.LEGACY_CONNECTION_ACTIVE),
            emptyList(), null, null, null)
        compose.setContent { VpnUiTheme(VpnTheme.DARK) { Column {
            VpnRouterPackageUpdates(RouterUpdateControlState(true, status), true, {})
        } } }
        compose.onNodeWithText(context.getString(R.string.router_updates_disconnect_before_update)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_updates_unsupported)).assertDoesNotExist()
    }

}
