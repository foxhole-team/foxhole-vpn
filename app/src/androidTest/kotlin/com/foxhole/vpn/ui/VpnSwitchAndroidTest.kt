package com.foxhole.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnSwitchAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyThemeUsesOneAccessibleSwitchWithOneCallbackPerTap() {
        var chooseTheme: (VpnTheme) -> Unit = {}
        var changes = 0
        compose.setContent {
            var theme by remember { mutableStateOf(VpnTheme.DARK) }
            var checked by remember { mutableStateOf(false) }
            chooseTheme = { theme = it; checked = false }
            VpnUiTheme(theme) { VpnSwitch(checked, { checked = it; changes++ }) }
        }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        VpnTheme.entries.forEach { theme ->
            compose.runOnIdle { chooseTheme(theme) }
            val toggle = compose.onNode(isToggleable()).assertIsOff()
            val bounds = toggle.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48f * density && bounds.height >= 48f * density)
            toggle.performClick().assertIsOn()
        }
        compose.runOnIdle { assertEquals(VpnTheme.entries.size, changes) }
    }
}
