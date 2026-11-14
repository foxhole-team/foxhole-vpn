package com.foxhole.vpn.ui

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnAppRouting
import com.foxhole.vpn.VpnTheme
import org.junit.Rule
import org.junit.Test

class VpnRoutingHelpAndroidTest {
    @get:Rule val compose = createComposeRule()
    @Test fun helpFollowsTheSelectedAppsOrAddressesSection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ui = VpnScenariosUiState()
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnBottomSheet(onDismiss = {}) {
                VpnScenariosSheet(VpnAppRouting(), true, false, ui, {}, {}, {})
            }
        } }
        fun helpLabel(title: Int) = context.getString(R.string.router_help_open, context.getString(title))
        compose.onNodeWithContentDescription(helpLabel(R.string.vpn_app_rules)).performClick()
        compose.onNodeWithText(context.getString(R.string.site_routing_modes_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.site_mask_format)).assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_routing_view_websites)).performClick()
        compose.onNodeWithContentDescription(helpLabel(R.string.vpn_web_rules)).performClick()
        compose.onNodeWithText(context.getString(R.string.site_mask_format)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.site_mask_exact_example)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.site_routing_modes_title)).assertDoesNotExist()
    }
    @Test fun longWebsiteExamplesUseVerticalRowsAtLargeFontScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(320.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) { VpnWebsiteMaskHelp() }
            }
        } }
        val example = compose.onNodeWithText(context.getString(R.string.site_mask_exact_example)).fetchSemanticsNode().boundsInRoot
        val scope = compose.onNodeWithText(context.getString(R.string.site_mask_exact_scope)).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(scope.top >= example.bottom)
        compose.onNodeWithText(context.getString(R.string.site_mask_range_example)).performScrollTo().assertIsDisplayed()
    }

    @Test fun inspectOnlyRouterHelpExplainsThatNoPackageWillBeInstalled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            VpnRouterHelpButton(R.string.help_router_title, R.string.router_help_setup_pair,
                canInstallRouter = false, canInspectRouter = true, actionLabelRes = R.string.help_router_open)
        } }
        compose.onNodeWithText(context.getString(R.string.help_router_open))
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(context.getString(R.string.help_router_install_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.help_router_install_available)).assertDoesNotExist()
    }

}
