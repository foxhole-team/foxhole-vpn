package com.foxhole.vpn.ui

import androidx.compose.ui.test.*
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
}
