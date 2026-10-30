package com.foxhole.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolChangeRequest
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.protocolChangeCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnProtocolChangeAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun retainedTunnelChoiceNeedsConfirmationAndCancelKeepsTheSameExpandedContext() {
        val controller = show(active = true, phase = VpnPhase.ERROR)
        openProtocols()
        compose.onNodeWithText("Owned second protocol").performClick()
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_protocol_change_body)).assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
        compose.onNodeWithText("Owned second protocol").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("a", controller.state.value.selectedProfile?.selectedProtocolOptionId)
            assertTrue(controller.state.value.canDisconnect)
            assertTrue(controller.confirmed.isEmpty())
            assertEquals(0, controller.immediate)
        }
    }

    @Test fun continueDispatchesExactlyOneConfirmedProtocolReplacementAndKeepsStopAvailable() {
        val controller = show(active = true)
        openProtocols()
        compose.onNodeWithText("Owned second protocol").performClick()
        compose.onNodeWithText(text(R.string.vpn_continue)).performClick()
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(VpnProtocolChangeRequest("owned", "b", "a", 17, 4)), controller.confirmed)
            assertEquals("b", controller.state.value.selectedProfile?.selectedProtocolOptionId)
            assertEquals(VpnPhase.STOPPING, controller.state.value.phase)
            assertTrue(controller.state.value.canDisconnect)
            assertEquals(0, controller.immediate)
            assertEquals(0, controller.connects)
        }
    }

    @Test fun changedProfileRevisionAndDisconnectInvalidatePendingConfirmations() {
        val controller = show(active = true)
        openProtocols()
        compose.onNodeWithText("Owned second protocol").performClick()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(profileRevision = 5) }
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertDoesNotExist()
        compose.onNodeWithText("Owned second protocol").performClick()
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertIsDisplayed()
        compose.runOnIdle { controller.disconnect() }
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(controller.confirmed.isEmpty())
            assertEquals("a", controller.state.value.selectedProfile?.selectedProtocolOptionId)
        }
    }

    @Test fun selectingTheCurrentOptionDoesNotOpenConfirmationOrReconnect() {
        val controller = show(active = true)
        openProtocols()
        compose.onNodeWithText("Owned first protocol").performClick()
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(controller.confirmed.isEmpty())
            assertEquals(0, controller.immediate)
            assertEquals(4L, controller.state.value.profileRevision)
        }
    }

    @Test fun disconnectedProtocolSelectionRemainsImmediate() {
        val controller = show(active = false)
        openProtocols()
        compose.onNodeWithText("Owned second protocol").performClick()
        compose.onNodeWithText(text(R.string.vpn_protocol_change_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, controller.immediate)
            assertTrue(controller.confirmed.isEmpty())
            assertEquals("b", controller.state.value.selectedProfile?.selectedProtocolOptionId)
            assertEquals(VpnPhase.IDLE, controller.state.value.phase)
        }
    }

    @Test fun connectedProfilesCanBeViewedAndImportedWithTlsConsentWithoutChangingTheTunnel() {
        val controller = show(active = true)
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).assertIsEnabled()
        compose.onNodeWithText(profile.name).performClick()
        compose.onNodeWithText(text(R.string.vpn_profiles)).assertIsDisplayed()
        compose.onNode(hasContentDescription(text(R.string.vpn_no_profile)) and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
        for (label in listOf(R.string.vpn_import_from_clipboard, R.string.vpn_import_file, R.string.vpn_import_qr)) {
            compose.onNodeWithText(text(label)).assertIsEnabled()
        }
        compose.onNodeWithText(text(R.string.vpn_import_manual)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_text)).performTextInput("owned-import")
        compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction()).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
        compose.runOnIdle { controller.importResult = VpnImportResult.Success(1, 0, 0) }
        compose.onNodeWithText(text(R.string.vpn_insecure_accept)).performClick()
        compose.onNodeWithText(text(R.string.vpn_profiles)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(false, true), controller.importConsents)
            assertEquals(VpnPhase.CONNECTED, controller.state.value.phase)
            assertEquals(17L, controller.state.value.connectionId)
            assertEquals(profile.id, controller.state.value.selectedProfileId)
            assertTrue(controller.state.value.hasOwnedTunnel)
            assertTrue(controller.confirmed.isEmpty())
            assertEquals(0, controller.immediate)
            assertEquals(0, controller.connects)
        }
        dismissRoot()
        compose.onNodeWithText(text(R.string.vpn_stop)).assertIsDisplayed().assertIsEnabled()
    }

    @Test fun profileActionsHaveEqualTopButtonsAndFullWidthDeleteWithOneLowerConfirmation() {
        val controller = show(active = false)
        openProfileActions()
        val copy = compose.onNode(hasText(text(R.string.vpn_profile_copy)) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val refreshNode = compose.onNodeWithText(text(R.string.vpn_refresh))
        refreshNode.assertIsNotEnabled()
        val refresh = refreshNode.fetchSemanticsNode().boundsInRoot
        val deleteNode = compose.onNodeWithContentDescription(context.getString(R.string.vpn_remove_named, profile.name))
        val delete = deleteNode.fetchSemanticsNode().boundsInRoot
        assertEquals(copy.width, refresh.width, 1f)
        assertEquals(copy.top, refresh.top, 1f)
        assertTrue(delete.width > copy.width * 1.9f)
        assertTrue(delete.top >= maxOf(copy.bottom, refresh.bottom))
        assertTrue(copy.height >= 48 * context.resources.displayMetrics.density)
        deleteNode.performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.vpn_remove_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_continue)).assertIsDisplayed()
        val cancelBounds = compose.onNodeWithText(text(R.string.vpn_cancel)).fetchSemanticsNode().boundsInRoot
        val continueBounds = compose.onNodeWithText(text(R.string.vpn_continue)).fetchSemanticsNode().boundsInRoot
        assertEquals(cancelBounds.width, continueBounds.width, 1f)
        assertEquals(cancelBounds.top, continueBounds.top, 1f)
        assertTrue(cancelBounds.height >= 56 * context.resources.displayMetrics.density)
        compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
        compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, controller.removals) }
    }

    @Test fun holdingAProfileOpensItsActionsInTheSameSheetWithoutAlsoExpandingProtocols() {
        val controller = show(active = false)
        compose.onNodeWithText(profile.name).performClick()
        val dialogId = compose.onNode(isDialog()).fetchSemanticsNode().id
        val header = compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name))
        assertEquals(context.getString(R.string.vpn_profile_actions_named, profile.name),
            header.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        header.performTouchInput { longClick(center) }
        compose.onNodeWithText(text(R.string.vpn_profile_actions_title)).assertIsDisplayed()
        compose.onNode(hasText(profile.name) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        assertEquals(dialogId, compose.onNode(isDialog()).fetchSemanticsNode().id)
        dismissRoot()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).assertIsDisplayed()
        compose.onNodeWithText("Owned second protocol").assertDoesNotExist()
        assertEquals(dialogId, compose.onNode(isDialog()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(profile.id, controller.state.value.selectedProfileId)
            assertTrue(controller.confirmed.isEmpty())
            assertEquals(0, controller.immediate)
            assertEquals(0, controller.connects)
        }
    }

    @Test fun anActiveTunnelAllowsWholeProfileCopyButKeepsRefreshAndRemovalDisabled() {
        val controller = show(active = true)
        compose.runOnIdle { controller.state.value = controller.state.value.copy(profiles = listOf(profile.copy(refreshable = true))) }
        openProfileActions()
        for (phase in listOf(VpnPhase.CONNECTED, VpnPhase.ERROR)) {
            compose.runOnIdle { controller.state.value = controller.state.value.copy(phase = phase) }
            compose.onNodeWithText(text(R.string.vpn_refresh)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(context.getString(R.string.vpn_remove_named, profile.name)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertIsEnabled().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.vpn_profile_copied)).assertIsDisplayed()
        }
        compose.runOnIdle {
            assertEquals(listOf(profile.id to null, profile.id to null), controller.exports)
            assertEquals(0, controller.removals)
            assertEquals(0, controller.connects)
            assertEquals(17L, controller.state.value.connectionId)
            assertTrue(controller.state.value.canDisconnect)
            assertTrue(controller.confirmed.isEmpty())
            assertEquals(0, controller.immediate)
        }
    }

    @Test fun importSecurityAndSettingsDetailsUseTheSameBottomSheetAndBackHistory() {
        val controller = show(active = false)
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        val importDialogId = compose.onNode(isDialog()).fetchSemanticsNode().id
        compose.onNodeWithText(text(R.string.vpn_import_manual)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_text)).performTextInput("owned-import")
        val importButton = compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction())
        importButton.assertIsDisplayed().assertIsEnabled()
        assertTrue(importButton.fetchSemanticsNode().boundsInRoot.height >= 48 * context.resources.displayMetrics.density)
        importButton.performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        assertEquals(importDialogId, compose.onNode(isDialog()).fetchSemanticsNode().id)
        compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
        compose.onNodeWithText("owned-import").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_manual)).assertIsDisplayed()
        dismissRoot()
        compose.onNodeWithContentDescription(text(R.string.vpn_settings)).performClick()
        val settingsDialogId = compose.onNode(isDialog()).fetchSemanticsNode().id
        compose.onNodeWithText(text(R.string.vpn_theme)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        assertEquals(settingsDialogId, compose.onNode(isDialog()).fetchSemanticsNode().id)
        assertTrue(compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height > context.resources.displayMetrics.heightPixels * .8f)
        compose.onNodeWithText(text(R.string.vpn_close)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.vpn_theme)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.vpn_settings)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false), controller.importConsents) }
    }

    @Test fun externalDraftReplacesImportOwnershipAndDiscardsAnOlderSecurityConsent() {
        val externalInput = mutableStateOf<String?>(null)
        val controller = show(active = false, importedFile = externalInput)
        val firstImport = CompletableDeferred<Unit>()
        compose.runOnIdle {
            controller.importGate = firstImport
            controller.importResult = VpnImportResult.Success(1)
            controller.allowedImportInputs += listOf("owned-second-import", "owned-third-import")
        }
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_manual)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_text)).performTextInput("owned-import")
        compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { controller.importConsents == listOf(false) } }
        compose.runOnIdle {
            assertEquals(listOf(false), controller.importConsents)
            externalInput.value = "owned-second-import"
        }
        compose.onNodeWithText("owned-second-import").assertIsDisplayed()
        compose.runOnIdle {
            controller.importGate = null
            controller.importResult = VpnImportResult.NeedsInsecureTlsConsent
            firstImport.complete(Unit)
        }
        compose.onNodeWithText("owned-second-import").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_profiles)).assertDoesNotExist()
        compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
        compose.runOnIdle { externalInput.value = "owned-third-import" }
        compose.onNodeWithText("owned-third-import").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_manual)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(false, false), controller.importConsents) }
    }

    @Test fun confirmationSlidesAndResizesWithoutReplacingItsBottomSheet() {
        show(active = false)
        openProfileActions()
        val remove = compose.onNodeWithContentDescription(context.getString(R.string.vpn_remove_named, profile.name))
        remove.assertIsDisplayed().assertIsEnabled()
        val pane = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        val before = pane.fetchSemanticsNode().boundsInRoot
        val dialogId = compose.onNode(isDialog()).fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        try {
            remove.performSemanticsAction(SemanticsActions.OnClick) { it() }
            stepTransition(text(R.string.vpn_remove_title))
            val interim = pane.fetchSemanticsNode().boundsInRoot
            val interimTitleX = compose.onNodeWithText(text(R.string.vpn_remove_title)).fetchSemanticsNode().positionInRoot.x
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val after = pane.fetchSemanticsNode().boundsInRoot
            val finalTitleX = compose.onNodeWithText(text(R.string.vpn_remove_title)).fetchSemanticsNode().positionInRoot.x
            assertTrue(kotlin.math.abs(before.height - after.height) > 1f)
            assertTrue(interim.height > minOf(after.height, before.height) && interim.height < maxOf(after.height, before.height))
            assertTrue(interimTitleX > finalTitleX)
            assertEquals(before.bottom, after.bottom, 1f)
            compose.onNodeWithText(text(R.string.vpn_cancel)).performSemanticsAction(SemanticsActions.OnClick) { it() }
            stepTransition(text(R.string.vpn_profile_actions_title))
            val returnInterim = pane.fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val returned = pane.fetchSemanticsNode().boundsInRoot
            assertTrue(returnInterim.height > minOf(after.height, returned.height) && returnInterim.height < maxOf(after.height, returned.height))
            assertEquals(before.height, returned.height, 1f)
            assertEquals(dialogId, compose.onNode(isDialog()).fetchSemanticsNode().id)
            compose.onAllNodes(isDialog()).assertCountEquals(1)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun stepTransition(incomingTitle: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            runCatching {
                val size = compose.onNodeWithText(incomingTitle).fetchSemanticsNode().size
                size.width > 0 && size.height > 0
            }.getOrDefault(false)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
    }

    private fun openProfile() {
        compose.onNodeWithText(profile.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
    }

    private fun openProtocols() {
        openProfile()
        compose.onNodeWithText("Owned first protocol").assertIsDisplayed()
        compose.onNodeWithText("Owned second protocol").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_refresh)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_remove_named, profile.name)).assertDoesNotExist()
    }

    private fun openProfileActions() {
        compose.onNodeWithText(profile.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name))
            .performTouchInput { longClick(center) }
        compose.onNodeWithText(text(R.string.vpn_profile_actions_title)).assertIsDisplayed()
    }

    private fun dismissRoot() {
        val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
        if (compose.onAllNodes(dismiss).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(dismiss).performSemanticsAction(SemanticsActions.Dismiss) { it() }
        } else compose.onNodeWithText(text(R.string.vpn_close)).performClick()
    }

    private fun show(active: Boolean, phase: VpnPhase = VpnPhase.CONNECTED, importedFile: MutableState<String?>? = null): Fixture {
        val controller = Fixture(VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id,
            phase = if (active) phase else VpnPhase.IDLE, hasOwnedTunnel = active, connectionId = if (active) 17 else null, profileRevision = 4))
        compose.setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            VpnUiTheme(settings.theme) {
                VpnApp(controller, state, settings, VpnUiActions({}, {}, {}, { importedFile?.value = null }), importedFile?.value, null)
            }
        }
        return controller
    }

    private class Fixture(initial: VpnUiState) : VpnController {
        override val state = MutableStateFlow(initial)
        override val settings = MutableStateFlow(VpnSettings())
        val confirmed = mutableListOf<VpnProtocolChangeRequest>()
        val importConsents = mutableListOf<Boolean>()
        val exports = mutableListOf<Pair<String, Set<String>?>>()
        val allowedImportInputs = mutableSetOf("owned-import")
        var importGate: CompletableDeferred<Unit>? = null
        var importResult: VpnImportResult = VpnImportResult.NeedsInsecureTlsConsent
        var immediate = 0
        var connects = 0
        var removals = 0
        override suspend fun awaitReady() = Unit
        override fun connectSelected() { connects++ }
        override fun disconnect() { state.value = state.value.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false, connectionId = null) }
        override fun selectProfile(id: String) = Unit
        override fun refreshLocation() = Unit
        override suspend fun selectProtocol(profileId: String, optionId: String) { immediate++; choose(optionId) }
        override suspend fun confirmProtocolChange(request: VpnProtocolChangeRequest) {
            if (!protocolChangeCurrent(request, state.value)) return
            confirmed += request
            choose(request.optionId)
            state.value = state.value.copy(phase = VpnPhase.STOPPING, location = null)
        }
        private fun choose(optionId: String) {
            state.value = state.value.copy(profileRevision = state.value.profileRevision + 1, profiles = state.value.profiles.map {
                it.copy(selectedProtocolOptionId = optionId, protocol = it.protocolOptions.single { option -> option.id == optionId }.protocol)
            })
        }
        override suspend fun importProfiles(text: String, allowInsecureTls: Boolean): VpnImportResult {
            assertTrue(text in allowedImportInputs)
            importConsents += allowInsecureTls
            val result = importResult
            val gate = importGate
            gate?.await()
            return result
        }
        override suspend fun refreshSubscription(profileId: String): VpnImportResult = error("Non-subscription refresh must remain disabled")
        override suspend fun exportProfile(profileId: String, optionIds: Set<String>?): String {
            exports += profileId to optionIds
            return "https://profiles.example.invalid/owned-subscription"
        }
        override suspend fun removeProfile(id: String) { removals++; state.value = state.value.copy(profiles = emptyList()) }
        override suspend fun updateSettings(settings: VpnSettings) { this.settings.value = settings }
    }

    private val profile = VpnProfileSummary("owned", "Owned multi-protocol profile", "VLESS", protocolOptions = listOf(
        VpnProtocolSummary("a", "Owned first protocol", "VLESS"), VpnProtocolSummary("b", "Owned second protocol", "HYSTERIA2")), selectedProtocolOptionId = "a")
}
