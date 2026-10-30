package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnHomeAdaptiveAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeKeepsTheProfileAndStartVisibleWithoutDraggingTheGlobe() { show(1f) }
    @Test fun landscapeWithLargeTextKeepsTheProfileAndStartVisible() { show(1.5f) }

    private fun show(fontScale: Float) {
        val profile = VpnProfileSummary("owned", "Owned adaptive profile", "VLESS")
        val state = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                VpnUiTheme(VpnTheme.NIGHT) {
                    Box(Modifier.requiredSize(600.dp, 280.dp).testTag("adaptive-home")) { VpnHome(state, {}, {}) }
                }
            }
        }
        compose.onNodeWithText(profile.name).assertIsDisplayed()
        val start = compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_start))
        start.assertIsDisplayed().assertIsEnabled()
        val content = compose.onNodeWithTag("adaptive-home").fetchSemanticsNode().boundsInRoot
        val button = start.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Landscape controls should rest near the safe lower edge", content.bottom - button.bottom <= 16f * density)
    }
}
