package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnRouterHelpAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun helpIconOpensTheExistingTextInABottomSheetAndBackKeepsTheParent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { VpnUiTheme(VpnTheme.DARK) {
            Column {
                Text("Router parent")
                VpnRouterHelpButton(R.string.vpn_router, R.string.router_help_setup_pair)
            }
        } }
        val label = context.getString(R.string.router_help_open, context.getString(R.string.vpn_router))
        compose.onNodeWithContentDescription(label).performClick()
        compose.onNodeWithText(context.getString(R.string.router_help_setup_pair)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_close)).assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.router_help_setup_pair)).assertDoesNotExist()
        compose.onNodeWithText("Router parent").assertIsDisplayed()
    }
}
