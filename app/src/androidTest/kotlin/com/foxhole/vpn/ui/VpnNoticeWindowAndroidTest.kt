package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnNoticeWindowAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneNoticeStaysAtScreenTopAcrossHomeSheetAndFloatingDialog() {
        val window = mutableStateOf(0)
        lateinit var notices: VpnNoticeState
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnNoticeScope {
                    val current = requireNotNull(LocalVpnNotices.current)
                    SideEffect { notices = current }
                    Box(Modifier.fillMaxSize()) { VpnNoticeWindow(); Text("Owned home") }
                    if (window.value == 1) VpnBottomSheet(onDismiss = { window.value = 0 }) { Text("Owned sheet") }
                    if (window.value == 2) VpnModalTheme {
                        AlertDialog(
                            onDismissRequest = { window.value = 0 },
                            icon = { VpnModalIcon(R.drawable.lin_info) },
                            title = { Text("Owned dialog") },
                            text = { Text("Owned content") },
                            confirmButton = { TextButton(onClick = { window.value = 0 }) { Text("Close owned dialog") } },
                        )
                    }
                }
            }
        }
        compose.runOnIdle { notices.show("Owned top notice", persistent = true) }
        assertTopNotice()
        compose.runOnIdle { window.value = 1 }
        compose.onNodeWithText("Owned sheet").assertIsDisplayed()
        assertTopNotice()
        compose.runOnIdle { window.value = 2 }
        compose.onNodeWithText("Owned dialog").assertIsDisplayed()
        assertTopNotice()
        compose.runOnIdle { window.value = 0 }
        assertTopNotice()
    }

    private fun assertTopNotice() {
        compose.onAllNodesWithText("Owned top notice").assertCountEquals(1)
        val node = compose.onNodeWithText("Owned top notice")
        node.assertIsDisplayed()
        val screenY = node.fetchSemanticsNode().positionOnScreen.y
        val height = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
        assertTrue("Notice must be near the display top: $screenY of $height", screenY >= 0 && screenY < height / 4f)
    }
}
