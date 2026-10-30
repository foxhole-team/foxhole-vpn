package com.foxhole.vpn.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnProfileSharingAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var shared = 0
    private var sent = 0
    private var refreshed = 0
    private var removed = 0

    private fun show(routerPaired: Boolean, shareEnabled: Boolean = true, editable: Boolean = true) {
        val profile = VpnProfileSummary(
            "review-profile", "Review profile", "VLESS", refreshable = true,
            protocolOptions = listOf(
                VpnProtocolSummary("first", "First protocol", "VLESS"),
                VpnProtocolSummary("second", "Second protocol", "VMESS"),
            ),
        )
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnProfileActionsSheet(
                    profile = profile,
                    busy = false,
                    shareEnabled = shareEnabled,
                    editable = editable,
                    routerPaired = routerPaired,
                    onShare = { shared++ },
                    onSendToRouter = { sent++ },
                    onRefresh = { refreshed++ },
                    onRemove = { removed++ },
                )
            }
        }
    }

    @Test fun sharingIsAvailableWithoutARouterPairing() {
        show(routerPaired = false)
        compose.onNodeWithText(context.getString(R.string.vpn_profile_share))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_profile_send_to_router)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, shared)
            assertEquals(0, sent)
        }
    }

    @Test fun aSavedPairingCanSendImmediatelyWithoutAnOnlineOrProtocolChooserState() {
        show(routerPaired = true)
        compose.onNodeWithText(context.getString(R.string.vpn_profile_send_to_router))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("First protocol").assertDoesNotExist()
        compose.onNodeWithText("Second protocol").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, sent)
            assertEquals(0, shared)
        }
    }

    @Test fun sharingAndSendingDoNotRequirePermissionToEditProfiles() {
        show(routerPaired = true, editable = false)
        compose.onNodeWithText(context.getString(R.string.vpn_profile_share)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_profile_send_to_router)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_refresh)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.vpn_remove)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, shared)
            assertEquals(1, sent)
            assertEquals(0, refreshed)
            assertEquals(0, removed)
        }
    }

    @Test fun unavailableProfileActionsCannotSendOrShare() {
        show(routerPaired = true, shareEnabled = false)
        compose.onNodeWithText(context.getString(R.string.vpn_profile_share)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.vpn_profile_send_to_router)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, shared)
            assertEquals(0, sent)
        }
    }

    @Test fun existingRefreshAndRemoveActionsStillEmitTheirOwnCallbacks() {
        show(routerPaired = true)
        compose.onNodeWithText(context.getString(R.string.vpn_refresh)).performClick()
        compose.onNodeWithText(context.getString(R.string.vpn_remove)).performClick()
        compose.runOnIdle {
            assertEquals(1, refreshed)
            assertEquals(1, removed)
            assertEquals(0, shared)
            assertEquals(0, sent)
        }
    }
}
