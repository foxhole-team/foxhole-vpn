package com.foxhole.vpn.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.R
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnProtocolBadgeAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedProtocolAppearsAsARaisedBadgeBesideTheProfileName() {
        val profile = VpnProfileSummary("owned", "Owned subscription", "HYSTERIA2")
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnHome(VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id), {}, {})
            }
        }
        val name = compose.onNodeWithText(profile.name, useUnmergedTree = true)
        val protocol = compose.onNodeWithText("Hysteria2", useUnmergedTree = true)
        name.assertIsDisplayed()
        protocol.assertIsDisplayed()
        assertTrue(protocol.fetchSemanticsNode().positionOnScreen.y < name.fetchSemanticsNode().positionOnScreen.y)
    }

    @Test fun onlyTheSelectedProfileExpiryAppearsBelowStatusWithoutBlockingControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expires = Instant.parse("2030-01-02T12:00:00Z").toEpochMilli()
        val dated = VpnProfileSummary("dated", "Owned dated subscription", "VLESS", subscriptionExpiresAt = expires)
        val ordinary = VpnProfileSummary("ordinary", "Owned ordinary profile", "VLESS")
        val state = mutableStateOf(VpnUiState(isReady = true, profiles = listOf(dated, ordinary), selectedProfileId = dated.id))
        val label = context.getString(R.string.vpn_subscription_until,
            requireNotNull(formatSubscriptionExpiry(expires, context.resources.configuration.locales[0], ZoneId.systemDefault())))
        compose.setContent { VpnUiTheme(VpnTheme.DARK) { VpnHome(state.value, {}, {}) } }
        val expiry = compose.onNodeWithText(label)
        expiry.assertIsDisplayed()
        val status = compose.onNodeWithText(context.getString(R.string.vpn_status_idle))
        assertTrue(expiry.fetchSemanticsNode().positionOnScreen.y > status.fetchSemanticsNode().positionOnScreen.y)
        compose.onNodeWithText(context.getString(R.string.vpn_start)).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(selectedProfileId = ordinary.id) }
        compose.onNodeWithText(label).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(selectedProfileId = dated.id, phase = VpnPhase.ERROR, hasOwnedTunnel = true, isReady = false)
        }
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_stop)).assertIsEnabled()
    }
}
