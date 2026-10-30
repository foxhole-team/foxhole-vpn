package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnBottomSheetAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pageChangesStayAlignedAndARapidBackFinishesWithoutLeavingOldContent() {
        val navigation = VpnSheetNavigation().apply { open(VpnSheetPage.Settings) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                Column(Modifier.width(360.dp)) {
                    VpnSheetPageTransition(requireNotNull(navigation.current)) { entry ->
                        val settings = entry.page == VpnSheetPage.Settings
                        Text(if (settings) "Owned settings" else "Owned profiles",
                            Modifier.fillMaxWidth().height(if (settings) 100.dp else 200.dp))
                    }
                }
            }
        }
        val left = compose.onNodeWithText("Owned settings").fetchSemanticsNode().boundsInRoot.left
        compose.runOnIdle { navigation.push(VpnSheetPage.Profiles) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        assertEquals(left, compose.onNodeWithText("Owned profiles").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.runOnIdle { assertTrue(navigation.back(requireNotNull(navigation.current).id)) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Owned settings").assertIsDisplayed()
        compose.onNodeWithText("Owned profiles").assertDoesNotExist()
    }

    @Test
    fun openingCanBeDismissedBeforeItSettlesAndReopened() {
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                if (visible.value) VpnBottomSheet(onDismiss = { visible.value = false }) {
                    Text("Owned sheet", Modifier.fillMaxWidth().height(600.dp))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertFalse(visible.value) }
        compose.onNodeWithText("Owned sheet").assertDoesNotExist()

        compose.runOnIdle { visible.value = true }
        val reopened = compose.onNodeWithText("Owned sheet")
        // The new Dialog's anchors need Android layout between test-clock frames.
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            reopened.isDisplayed()
        }
        reopened.assertIsDisplayed()
        compose.runOnIdle { assertTrue(visible.value) }
    }

    @Test
    fun downwardDragDismissesTallSheetWithoutAnIntermediateAnchor() {
        val visible = mutableStateOf(true)
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                if (visible.value) VpnBottomSheet(onDismiss = { visible.value = false }) {
                    Text("Owned sheet", Modifier.fillMaxWidth().height(600.dp))
                }
            }
        }
        compose.onNodeWithText("Owned sheet").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performTouchInput {
            swipe(Offset(width * .5f, height * .1f), Offset(width * .5f, height * .95f), 100)
        }
        compose.runOnIdle { assertFalse(visible.value) }
        compose.onNodeWithText("Owned sheet").assertDoesNotExist()
    }

    @Test
    fun theFullWidthCloseButtonDismissesNestedContentWithoutInvokingBack() {
        val visible = mutableStateOf(true)
        var backCalls = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                if (visible.value) VpnBottomSheet(onDismiss = { visible.value = false }, onBack = { backCalls++ }) {
                    Text("Owned nested sheet", Modifier.fillMaxWidth().height(600.dp))
                }
            }
        }
        val close = compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_close))
        close.assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(visible.value); assertTrue(backCalls == 0) }
    }
}
