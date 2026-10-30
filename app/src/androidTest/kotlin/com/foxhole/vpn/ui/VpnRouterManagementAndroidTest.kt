package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.router.RouterDiscoveryKind
import com.foxhole.vpn.router.RouterDiscoveryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VpnRouterManagementAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun entryExplainsLanUseAndRestoresTheApprovedPlatformsBeforeCatalogLoading() {
        var findRequests = 0
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnRouterEntrySheet(onSetup = {}, onPair = {}, onFind = { findRequests++ }, wifiConnected = true)
        } }
        compose.onNodeWithText(context.getString(R.string.vpn_router_local_network_purpose)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_review_supported_platforms)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_review_catalog_pending)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_review_find_router)).performClick()
        compose.runOnIdle { assertEquals(1, findRequests) }
    }

    @Test fun notFoundOffersRetryThenManualEntryWithoutCompatibilityClaims() {
        var retries = 0
        var manual = 0
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnRouterEntrySheet(onSetup = {}, onPair = { manual++ }, onFind = { retries++ },
                discovery = RouterDiscoveryResult("10.23.45.1", RouterDiscoveryKind.NOT_FOUND, discoveryId = "synthetic-not-found"),
                wifiConnected = true)
        } }
        compose.onNodeWithText(context.getString(R.string.router_review_compatibility)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.router_review_supported_platforms)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.router_review_wifi_connected)).assertIsDisplayed()
        val retry = compose.onNodeWithText(context.getString(R.string.router_review_retry_search))
        val enter = compose.onNodeWithText(context.getString(R.string.router_review_enter_manually))
        assertTrue(retry.fetchSemanticsNode().boundsInRoot.center.y < enter.fetchSemanticsNode().boundsInRoot.center.y)
        retry.performClick()
        enter.performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, manual) }
    }

    @Test fun reachableUnknownPackageRetainsCompatibilityAndAnHonestStatus() {
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnRouterEntrySheet(onSetup = {}, onPair = {}, onFind = {},
                discovery = RouterDiscoveryResult("10.23.45.1", RouterDiscoveryKind.UNKNOWN, discoveryId = "synthetic-unknown"),
                wifiConnected = true)
        } }
        compose.onNodeWithText(context.getString(R.string.router_review_package_unknown)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_review_supported_platforms)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_review_not_found)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.router_review_wifi_connected)).assertIsDisplayed()
    }

    @Test fun noPhysicalWifiShowsTheBlockedIndicatorAndDisablesSearch() {
        var requests = 0
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnRouterEntrySheet(onSetup = {}, onPair = {}, onFind = { requests++ }, wifiConnected = false)
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_router_error_wifi)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_review_find_router)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun sharingAndRemovalRemainEqualWidthOnOneRow() {
        var shared = 0
        var removed = 0
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnRouterProfileActionsFooter(true, { shared++ }, { removed++ })
        } }
        val share = compose.onNodeWithText(context.getString(R.string.router_review_share))
        val remove = compose.onNodeWithText(context.getString(R.string.vpn_remove))
        share.assertIsDisplayed().performClick()
        remove.assertIsDisplayed().performClick()
        val a = share.fetchSemanticsNode().boundsInRoot
        val b = remove.fetchSemanticsNode().boundsInRoot
        assertTrue(abs(a.center.y - b.center.y) <= 1f)
        assertTrue(a.left < b.left)
        assertTrue(abs(a.width - b.width) <= 1f)
        compose.runOnIdle { assertEquals(1, shared); assertEquals(1, removed) }
    }

    @Test fun remoteProfileShowsInlineImportWithoutActionsOrQrButtons() {
        var imported = 0
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            Column { VpnRouterProfileManagementContent("Review profile", "Hysteria2",
                "hysteria2://SyntheticOnly@router.example:443#Review", false, true, true) { imported++ } }
        } }
        compose.onNodeWithText("Review profile").assertIsDisplayed()
        compose.onNodeWithText("Hysteria2").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.router_control_client_actions_button)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.router_control_show_qr)).assertDoesNotExist()
        val qr = context.getString(R.string.router_control_qr_description, "Review profile")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(qr).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.router_control_import_phone)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, imported) }
    }

    @Test fun aDeviceCannotSelectAnInactiveGlobalRouterVpnProfile() {
        var selected: Boolean? = null
        compose.setContent { VpnUiTheme(VpnTheme.DARK) { VpnRouterDeviceRouteDropdown(null, true, false) { selected = it } } }
        compose.onNodeWithText(context.getString(R.string.router_review_router_default)).performClick()
        compose.onNodeWithText(context.getString(R.string.router_review_device_vpn)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.router_review_direct)).performClick()
        compose.runOnIdle { assertEquals(false, selected) }
    }

    @Test fun aDeviceUsesTheGlobalRouterVpnChoiceWithoutIndependentProfiles() {
        var selected: Boolean? = null
        compose.setContent { VpnUiTheme(VpnTheme.DARK) { VpnRouterDeviceRouteDropdown("direct", true, true) { selected = it } } }
        compose.onNodeWithText(context.getString(R.string.router_review_direct)).performClick()
        compose.onNodeWithText(context.getString(R.string.router_review_device_vpn)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, selected) }
    }
}
