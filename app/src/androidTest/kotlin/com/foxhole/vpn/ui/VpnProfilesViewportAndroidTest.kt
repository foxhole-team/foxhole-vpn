package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnProfilesViewportAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val profiles = (1..7).map { VpnProfileSummary("owned-$it", "Profile $it", "VLESS") }
    private lateinit var shownUi: VpnProfilesUiState

    private fun show(height: Int = 680, fontScale: Float = 1f, profileList: List<VpnProfileSummary> = profiles,
        onActions: (VpnProfileSummary) -> Unit = {}, onProtocol: (String, String) -> Unit = { _, _ -> }) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                VpnUiTheme(VpnTheme.DARK) {
                    Column(Modifier.requiredWidth(360.dp).requiredHeight(height.dp)) {
                        Box(Modifier.weight(1f, fill = false)) {
                            VpnProfilesSheet(VpnUiState(isReady = true, profiles = profileList, selectedProfileId = profileList.first().id), false,
                                remember { VpnProfilesUiState() }.also { shownUi = it }, {}, {}, onActions, onProtocol)
                        }
                        OutlinedButton({}, Modifier.fillMaxWidth().height(56.dp)) { Text("Owned close") }
                    }
                }
            }
        }
    }

    @Test fun sixDistinctCollapsedProfilesFitAndTheSeventhUsesTheVisibleScrollbar() {
        show()
        profiles.take(6).forEach { compose.onNodeWithText(it.name).assertIsDisplayed() }
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
        compose.onNodeWithTag(VpnProfileScrollbarTag).assertIsDisplayed()
        compose.runOnIdle {
            val layout = shownUi.listState.layoutInfo
            val complete = layout.visibleItemsInfo.filter { it.offset >= layout.viewportStartOffset &&
                it.offset + it.size <= layout.viewportEndOffset }.map { it.index }
            assertEquals(listOf(0, 1, 2, 3, 4, 5), complete)
            assertEquals(0, shownUi.listState.firstVisibleItemIndex)
        }
        compose.onNodeWithTag(VpnProfilesListTag).performScrollToIndex(6)
        compose.onNodeWithText(profiles.last().name).assertIsDisplayed()
        compose.onNodeWithTag(VpnProfileScrollbarTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
    }

    @Test fun shortScreensAndLargeTextKeepTheHeaderAndCloseWhileEveryProfileRemainsReachable() {
        show(height = 420, fontScale = 2f)
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
        compose.onNodeWithTag(VpnProfileScrollbarTag).assertIsDisplayed()
        compose.onNodeWithTag(VpnProfilesListTag).performScrollToIndex(6)
        compose.onNodeWithText(profiles.last().name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
    }

    @Test fun tappingAProfileStillOpensItsProtocolsAndHoldingItStillOpensActions() {
        val profile = profiles.first().copy(protocolOptions = listOf(
            VpnProtocolSummary("vless", "Owned VLESS route", "VLESS"),
            VpnProtocolSummary("hy2", "Owned Hysteria route", "HYSTERIA2"),
        ), selectedProtocolOptionId = "vless")
        val actions = mutableListOf<String>()
        val selections = mutableListOf<Pair<String, String>>()
        show(profileList = listOf(profile) + profiles.drop(1), onActions = { actions += it.id },
            onProtocol = { id, option -> selections += id to option })
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithText("Owned Hysteria route").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(profile.id to "hy2"), selections) }
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_collapse_named, profile.name)).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(profile.id), actions) }
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
    }

    @Test fun a4096OptionSubscriptionKeepsOffscreenRowsUncomposedAndTheLastRouteSelectable() {
        val options = (1..4096).map { VpnProtocolSummary("owned-option-$it", "Owned route $it", "VLESS") }
        val profile = profiles.first().copy(protocolOptions = options, selectedProtocolOptionId = options.first().id)
        val selections = mutableListOf<Pair<String, String>>()
        show(profileList = listOf(profile), onProtocol = { id, option -> selections += id to option })
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithText(options.first().name).assertIsDisplayed()
        compose.onNodeWithText(options.last().name).assertDoesNotExist()
        compose.onNodeWithTag(vpnProtocolListTag(profile.id)).performScrollToIndex(options.lastIndex)
        compose.onNodeWithText(options.last().name).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(profile.id to options.last().id), selections) }
        compose.onNodeWithText("Owned close").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_collapse_named, profile.name)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithText(options.first().name).assertIsDisplayed()
        compose.onNodeWithText(options.last().name).assertDoesNotExist()
        repeat(3) { compose.onNodeWithTag(vpnProtocolListTag(profile.id)).performTouchInput { swipeDown(durationMillis = 150) } }
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
    }

    @Test fun scrollingPastTheLastProtocolReachesTheFollowingProfileWithoutClosingTheSheet() {
        val options = (1..4096).map { VpnProtocolSummary("owned-option-$it", "Owned route $it", "VLESS") }
        val profile = profiles.first().copy(protocolOptions = options)
        show(profileList = listOf(profile) + profiles.drop(1))
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithTag(vpnProtocolListTag(profile.id)).performScrollToIndex(options.lastIndex)
        val distance = with(compose.density) { 64.dp.toPx() }
        compose.onNodeWithTag(vpnProtocolListTag(profile.id)).performTouchInput {
            swipe(center, center - Offset(0f, distance), durationMillis = 500)
        }
        compose.runOnIdle { assertTrue(shownUi.listState.firstVisibleItemScrollOffset > 0 || shownUi.listState.firstVisibleItemIndex > 0) }
        compose.onNodeWithText(profiles[1].name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText("Owned close").assertIsDisplayed()
    }

    @Test fun closingAndReopeningUsesANewCollapsedListAtTheBeginning() {
        val options = (1..4096).map { VpnProtocolSummary("owned-reset-$it", "Owned reset route $it", "VLESS") }
        val profile = profiles.first().copy(protocolOptions = options)
        val list = listOf(profile) + profiles.drop(1)
        lateinit var latest: VpnProfilesUiState
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    VpnBottomSheet(onDismiss = { visible = false }) {
                        val ui = remember { VpnProfilesUiState() }.also { latest = it }
                        VpnProfilesSheet(VpnUiState(isReady = true, profiles = list), false, ui, {}, {}, {}, { _, _ -> })
                    }
                } else OutlinedButton({ visible = true }) { Text("Owned reopen") }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithTag(vpnProtocolListTag(profile.id)).performScrollToIndex(options.lastIndex)
        compose.onNodeWithText(options.last().name).assertIsDisplayed()
        compose.onNodeWithTag(VpnProfilesListTag).performScrollToIndex(6)
        compose.onNodeWithText(context.getString(R.string.vpn_close)).assertIsDisplayed().performClick()
        compose.onNodeWithText("Owned reopen").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).assertIsDisplayed()
        compose.onNodeWithText(options.last().name).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(null, latest.expandedProfile)
            assertEquals(0, latest.listState.firstVisibleItemIndex)
            assertEquals(0, latest.listState.firstVisibleItemScrollOffset)
            assertTrue(latest.listState.canScrollForward)
        }
        compose.onNodeWithText(context.getString(R.string.vpn_close)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithText(options.first().name).assertIsDisplayed()
        compose.onNodeWithText(options.last().name).assertDoesNotExist()
    }

    @Test fun downwardOverscrollInTheProfilePickerKeepsTheSheetOpenAndCloseStillWorks() {
        var visible by mutableStateOf(true)
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                if (visible) VpnBottomSheet(onDismiss = { visible = false }) {
                    VpnProfilesSheet(VpnUiState(isReady = true, profiles = profiles), false,
                        remember { VpnProfilesUiState() }, {}, {}, {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag(VpnProfilesListTag).performScrollToIndex(6)
        repeat(3) { compose.onNodeWithTag(VpnProfilesListTag).performTouchInput { swipeDown(durationMillis = 150) } }
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.vpn_close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(!visible) }
    }

    @Test fun downwardDragFromTheProfileTitleDismissesTheSameSheet() {
        var visible by mutableStateOf(true)
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                if (visible) VpnBottomSheet(onDismiss = { visible = false }) {
                    VpnProfilesSheet(VpnUiState(isReady = true, profiles = profiles), false,
                        remember { VpnProfilesUiState() }, {}, {}, {}, { _, _ -> })
                }
            }
        }
        val distance = with(compose.density) { 300.dp.toPx() }
        compose.onNodeWithText(context.getString(R.string.vpn_profiles)).performTouchInput {
            swipe(center, center + Offset(0f, distance), durationMillis = 180)
        }
        compose.runOnIdle { assertTrue(!visible) }
    }

    @Test fun expandingAndCollapsingResizesTheSheetContinuouslyWithItsBottomFixed() {
        val profile = profiles.first().copy(protocolOptions = listOf(
            VpnProtocolSummary("vless", "Owned first option", "VLESS"),
            VpnProtocolSummary("hy2", "Owned second option", "HYSTERIA2"),
        ))
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnBottomSheet(onDismiss = {}) {
                    VpnProfilesSheet(VpnUiState(isReady = true, profiles = listOf(profile)), false,
                        remember { VpnProfilesUiState() }, {}, {}, {}, { _, _ -> })
                }
            }
        }
        val pane = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        val before = pane.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            fun toggle(expanded: Boolean) {
                val label = context.getString(if (expanded) R.string.vpn_profile_collapse_named else R.string.vpn_profile_expand_named, profile.name)
                compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
            }
            toggle(false)
            val duringExpand = pane.fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val expanded = pane.fetchSemanticsNode().boundsInRoot
            assertTrue(duringExpand.height > before.height && duringExpand.height < expanded.height)
            assertEquals(before.bottom, expanded.bottom, 1f)
            toggle(true)
            val duringCollapse = pane.fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val collapsed = pane.fetchSemanticsNode().boundsInRoot
            assertTrue(duringCollapse.height > collapsed.height && duringCollapse.height < expanded.height)
            assertEquals(before.height, collapsed.height, 1f)
            assertEquals(before.bottom, collapsed.bottom, 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun aTapOnAnOptionDuringCollapseCannotSelectAHiddenProtocol() {
        val profile = profiles.first().copy(protocolOptions = listOf(
            VpnProtocolSummary("vless", "Owned first option", "VLESS"),
            VpnProtocolSummary("hy2", "Owned second option", "HYSTERIA2"),
        ))
        val selections = mutableListOf<Pair<String, String>>()
        show(profileList = listOf(profile), onProtocol = { id, option -> selections += id to option })
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithText("Owned first option").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_collapse_named, profile.name))
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Owned first option").assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertTrue(selections.isEmpty()) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
