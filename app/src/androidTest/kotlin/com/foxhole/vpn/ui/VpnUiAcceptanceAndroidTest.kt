package com.foxhole.vpn.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnDeviceLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnUiAcceptanceAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var permissionRequests = 0
    private var fileRequests = 0
    private var qrRequests = 0
    private var consumedInputs = 0
    private var consumedErrors = 0
    private var observedBackground = Color.Unspecified
    private var regularFont: FontFamily? = null

    @Test
    fun emptyHomeHasDisabledStartAndProfilePromptOpensImport() {
        val controller = show(VpnUiState(isReady = true))
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        globe.assertIsDisplayed().assertHasClickAction()
        globe.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        compose.onNodeWithText(text(R.string.vpn_start)).assertIsNotEnabled()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertNoRemovedSurfaces()

        compose.onNodeWithText(text(R.string.vpn_profile_none)).performClick()

        compose.onNodeWithText(text(R.string.vpn_import_manual)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_profiles)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
        }
    }

    @Test
    fun plusOpensImportChoicesAndManualImportPreservesInsecureTlsConsent() {
        val controller = show(VpnUiState(isReady = true))
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_from_clipboard)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_import_file)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_import_manual)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_text)).performTextInput("owned-ui-profile")
        val importButton = compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction())
        importButton.assertIsDisplayed().assertIsEnabled()
        assertTrue(importButton.fetchSemanticsNode().boundsInRoot.height >= 48 * context.resources.displayMetrics.density)
        importButton.performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
        assertRegularFont(compose.onNodeWithText(text(R.string.vpn_insecure_accept)))
        compose.runOnIdle { assertEquals(listOf(false), controller.importConsents) }

        compose.onNodeWithText(text(R.string.vpn_insecure_accept)).performClick()

        compose.onNodeWithText(text(R.string.vpn_profiles)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(false, true), controller.importConsents)
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
        }
    }

    @Test
    fun fileImportLeavesTheSheetThroughTheExistingDocumentAction() {
        show(VpnUiState(isReady = true))
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_file)).performClick()
        compose.runOnIdle { assertEquals(1, fileRequests) }
        compose.onNodeWithText(text(R.string.vpn_import_manual)).assertDoesNotExist()
    }

    @Test
    fun qrImportOpensTheCameraActionWithoutStartingOrImporting() {
        val controller = show(VpnUiState(isReady = true))
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        val scan = compose.onNodeWithText(text(R.string.vpn_import_qr))
        scan.assertIsDisplayed()
        assertRegularFont(scan)
        scan.performClick()
        compose.onNodeWithText(text(R.string.vpn_import_manual)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, qrRequests)
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertTrue(controller.importConsents.isEmpty())
        }
    }

    @Test
    fun scannedProfileWaitsForImportAndKeepsInsecureConsent() {
        val controller = show(VpnUiState(isReady = true), importedText = "owned-scanned-profile")
        compose.onNodeWithText("owned-scanned-profile").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, consumedInputs)
            assertTrue(controller.importConsents.isEmpty())
            assertEquals(0, controller.connectCalls)
        }
        compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction()).performClick()
        compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false), controller.importConsents) }
    }

    @Test
    fun directProtocolSelectionAndWholeProfileCopyKeepTheirSheetHeightWhileBusy() {
        val optionName = "Fast route with a deliberately long protocol option name"
        val profile = OwnedProfile.copy(
            protocolOptions = listOf(
                VpnProtocolSummary("vless", "Secure route", "VLESS"),
                VpnProtocolSummary("hy2", optionName, "HYSTERIA2"),
            ),
            selectedProtocolOptionId = "vless",
        )
        val controller = show(readyProfile().copy(profiles = listOf(profile)))
        compose.onNodeWithText(profile.name).performClick()
        val inSheet = hasAnyAncestor(isDialog())
        val profileBounds = compose.onNode(hasText(profile.name) and inSheet, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badgeBounds = compose.onNode(hasText(protocolDisplayName(profile.protocol)) and inSheet, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(badgeBounds.left >= profileBounds.right)
        assertTrue(badgeBounds.left - profileBounds.right <= with(compose.density) { 12.dp.toPx() })
        assertTrue(badgeBounds.top < profileBounds.top)
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
        compose.onNodeWithText(optionName).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertDoesNotExist()
        val optionBounds = compose.onNode(hasText(optionName) and inSheet, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val optionBadgeBounds = compose.onNode(hasText(protocolDisplayName("HYSTERIA2")) and inSheet, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(optionBadgeBounds.left >= optionBounds.right)
        assertTrue(optionBadgeBounds.left - optionBounds.right <= with(compose.density) { 12.dp.toPx() })
        assertTrue(optionBadgeBounds.top < optionBounds.top)
        val sheet = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        val before = sheet.fetchSemanticsNode().boundsInRoot
        val selected = CompletableDeferred<Unit>()
        compose.runOnIdle { controller.selectionGate = selected }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(optionName).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(before, sheet.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { selected.complete(Unit) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(profile.id to "hy2"), controller.protocolSelections) }

        openProfileActionsFromList(profile.name)
        val copied = CompletableDeferred<Unit>()
        compose.runOnIdle { controller.exportGate = copied }
        val beforeCopy = sheet.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.vpn_profile_copy)).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(beforeCopy, sheet.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { copied.complete(Unit) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(beforeCopy, sheet.fetchSemanticsNode().boundsInRoot)
        compose.onAllNodesWithText(text(R.string.vpn_profile_copied)).assertCountEquals(1)
        compose.runOnIdle { assertEquals(listOf(profile.id to null), controller.exportRequests) }
        assertNoticeAtTop(text(R.string.vpn_profile_copied))
    }

    @Test
    fun aRuntimeFailureOverridesOldExternalFeedbackAndKeepsStopAvailable() {
        val external = mutableStateOf<String?>("Owned file error")
        val controller = show(readyProfile(), external = external)
        compose.onNodeWithText("Owned file error").assertIsDisplayed()
        compose.runOnIdle {
            controller.state.value = controller.state.value.copy(
                isReady = false, phase = VpnPhase.ERROR, hasOwnedTunnel = true, message = "Owned tunnel stop failure",
            )
        }
        compose.onNodeWithText("Owned file error").assertDoesNotExist()
        assertNoticeAtTop("Owned tunnel stop failure")
        compose.onNodeWithText(text(R.string.vpn_stop)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, controller.disconnectCalls) }
    }

    @Test
    fun openingManualImportPreservesThePersistentErrorAboveTheDialog() {
        show(VpnUiState(isReady = true, phase = VpnPhase.ERROR, message = "Owned persistent failure"))
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_manual)).performClick()
        compose.onNodeWithText(text(R.string.vpn_import_text)).assertIsDisplayed()
        compose.onAllNodesWithText("Owned persistent failure").assertCountEquals(1)
        assertNoticeAtTop("Owned persistent failure")
    }

    @Test
    fun identicalExternalErrorsCanBePresentedAgainAfterAcknowledgement() {
        val external = mutableStateOf<String?>("Owned repeated error")
        show(readyProfile(), external = external)
        compose.onNodeWithText("Owned repeated error").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.vpn_close)).performClick()
        compose.onNodeWithText("Owned repeated error").assertDoesNotExist()
        compose.runOnIdle { external.value = "Owned repeated error" }
        compose.onNodeWithText("Owned repeated error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, consumedErrors) }
    }

    @Test
    fun twoRefreshCallbacksBeforeAFrameLaunchOnlyOneRequest() {
        val profile = OwnedProfile.copy(refreshable = true)
        val controller = show(readyProfile().copy(profiles = listOf(profile)))
        val released = CompletableDeferred<Unit>()
        compose.runOnIdle { controller.refreshGate = released }
        compose.onNodeWithText(profile.name).performClick()
        openProfileActionsFromList(profile.name)
        val refresh = compose.onNodeWithText(text(R.string.vpn_refresh))
        val click = requireNotNull(refresh.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { click(); click() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1, controller.refreshCalls) }
        refresh.assertIsNotEnabled()
        compose.runOnIdle { released.complete(Unit) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.vpn_profiles)).assertIsDisplayed()
        openProfileActionsFromList(profile.name)
        compose.onNodeWithText(text(R.string.vpn_refresh)).assertIsEnabled()
    }

    @Test
    fun globeDoesNotAutoRotateOrClaimAServerWithoutValidatedGeography() {
        val controller = show(readyProfile())
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        awaitGlobeReady()
        val initial = globe.captureToImage().asAndroidBitmap()
        globe.performTouchInput {
            swipe(Offset(width * .4f, height * .5f), Offset(width * .7f, height * .55f), 300L)
        }
        awaitGlobeReady()
        val moved = globe.captureToImage().asAndroidBitmap()
        assertFalse(initial.sameAs(moved))

        compose.mainClock.advanceTimeBy(5_000L)
        assertTrue(moved.sameAs(globe.captureToImage().asAndroidBitmap()))
        compose.runOnIdle {
            controller.state.value = controller.state.value.copy(phase = VpnPhase.VALIDATING)
        }
        assertTrue(moved.sameAs(globe.captureToImage().asAndroidBitmap()))
        compose.runOnIdle {
            controller.state.value = controller.state.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        }
        val connected = globe.captureToImage().asAndroidBitmap()
        assertTrue(moved.sameAs(connected))
        compose.mainClock.advanceTimeBy(5_000L)
        assertTrue(connected.sameAs(globe.captureToImage().asAndroidBitmap()))
        compose.runOnIdle {
            controller.state.value = controller.state.value.copy(phase = VpnPhase.IDLE, hasOwnedTunnel = false)
        }
        assertTrue(moved.sameAs(globe.captureToImage().asAndroidBitmap()))
        val actions = globe.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(7, actions.size)
            assertTrue(actions.single { it.label == text(R.string.vpn_globe_reset) }.action())
        }
        awaitGlobeReady()
        assertTrue(initial.sameAs(globe.captureToImage().asAndroidBitmap()))
        compose.runOnIdle {
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
        }
    }

    @Test
    fun hydrationDisablesActionAndReadyProfileCallsOnlyThePermissionBoundary() {
        val controller = show(VpnUiState())
        compose.onNodeWithText(text(R.string.vpn_start)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.vpn_loading)).assertExists()
        compose.runOnIdle { controller.state.value = readyProfile() }

        compose.onNodeWithText(text(R.string.vpn_start))
            .assertIsEnabled().assertHasClickAction().performClick()

        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
        }
    }

    @Test
    fun retainedErrorWithoutAProfileRemainsAccessibleStopAndHidesStaleLocation() {
        val controller = show(
            VpnUiState(
                isReady = false,
                phase = VpnPhase.ERROR,
                hasOwnedTunnel = true,
                location = OwnedLocation,
                message = "Owned tunnel verification failure",
            ),
        )
        val stop = compose.onNodeWithText(text(R.string.vpn_stop))
        stop.assertIsEnabled().assertHasClickAction()
        stop.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.vpn_status_error)),
        )
        compose.onNodeWithText(OwnedLocation.city).assertDoesNotExist()
        compose.onNodeWithText(OwnedLocation.country).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_status_connected)).assertDoesNotExist()
        compose.onNodeWithText("Owned tunnel verification failure").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).assertIsNotEnabled()

        stop.performClick()

        compose.runOnIdle {
            assertEquals(1, controller.disconnectCalls)
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
        }
    }

    @Test
    fun serverCalloutRequiresValidatedCoordinatesAndSurvivesRotation() {
        val controller = show(readyProfile().copy(phase = VpnPhase.VALIDATING, location = OwnedLocation))
        compose.onNodeWithText(OwnedLocation.city).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_location_loading_device)).assertDoesNotExist()
        compose.runOnIdle {
            controller.state.value = controller.state.value.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
        }
        awaitGlobeReady()
        globeAction(R.string.vpn_globe_server)
        compose.onNodeWithText(OwnedLocation.city).assertIsDisplayed()
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        globe.performTouchInput { swipe(Offset(width * .4f, height * .6f), Offset(width * .48f, height * .6f), 300L) }
        awaitGlobeReady()
        compose.onNodeWithText(OwnedLocation.city).assertIsDisplayed()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(phase = VpnPhase.ERROR) }
        compose.onNodeWithText(OwnedLocation.city).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_stop)).assertIsEnabled()
    }

    @Test
    fun deviceCalloutAppearsOnceAndGlobeTapDismissesItWithoutStartingVpn() {
        val device = VpnLocation("US", "Owned device city", "Owned device country", "192.0.2.8", 40.0, -75.0)
        val controller = show(readyProfile().copy(deviceLocation = VpnDeviceLocation(device, DeviceLocationStatus.CURRENT)))
        awaitGlobeReady()
        compose.onNodeWithText(device.city).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_location_device_caption)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.vpn_globe)).performClick()
        awaitGlobeReady()
        compose.onNodeWithText(device.city).assertDoesNotExist()
        compose.runOnIdle {
            controller.state.value = controller.state.value.copy(message = "Synthetic update")
        }
        compose.onNodeWithText(device.city).assertDoesNotExist()
        globeAction(R.string.vpn_globe_device)
        compose.onNodeWithText(device.city).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
        }
    }

    @Test
    fun tappingTheProfileRowExpandsDetailsAndSelectionStillNeedsTheRadioButton() {
        val second = VpnProfileSummary("owned-second", "Second owned profile", "WireGuard")
        val controller = show(readyProfile().copy(profiles = listOf(OwnedProfile, second)))
        compose.onNodeWithText(OwnedProfile.name).performClick()
        compose.onNodeWithText(text(R.string.vpn_import)).assertDoesNotExist()
        compose.onNodeWithText(second.name).performClick()
        compose.runOnIdle { assertEquals(OwnedProfile.id, controller.state.value.selectedProfileId) }
        compose.onNodeWithText(context.getString(R.string.vpn_profile_protocol, second.protocol)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasAnyAncestor(hasText(second.name))).performClick()
        compose.runOnIdle { assertEquals(second.id, controller.state.value.selectedProfileId) }
        compose.onNodeWithText(context.getString(R.string.vpn_profile_protocol, second.protocol)).assertIsDisplayed()
        val removeSecond = context.getString(R.string.vpn_remove_named, second.name)
        compose.onNodeWithContentDescription(removeSecond).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertDoesNotExist()
        openProfileActionsFromList(second.name)
        compose.onNodeWithContentDescription(removeSecond).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_remove_named, OwnedProfile.name)).assertDoesNotExist()
        compose.onNodeWithContentDescription(removeSecond).performClick()
        compose.onNodeWithText(text(R.string.vpn_remove_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, controller.state.value.profiles.size) }
        compose.onNode(hasText(text(R.string.vpn_continue)) and hasAnyAncestor(hasAnyDescendant(hasText(text(R.string.vpn_remove_title))))).performClick()
        compose.runOnIdle { assertEquals(listOf(OwnedProfile), controller.state.value.profiles) }
    }

    @Test
    fun closingTheListOrProfileActionsResetsExpandedProtocolsOnTheNextOpening() {
        val profile = OwnedProfile.copy(protocolOptions = listOf(
            VpnProtocolSummary("vless", "Owned reset VLESS", "VLESS"),
            VpnProtocolSummary("hy2", "Owned reset Hysteria", "HYSTERIA2"),
        ), selectedProtocolOptionId = "vless")
        val controller = show(readyProfile().copy(profiles = listOf(profile), phase = VpnPhase.CONNECTED,
            hasOwnedTunnel = true, connectionId = 17L))
        for (openActions in listOf(false, true)) {
            compose.onNodeWithText(profile.name).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
            compose.onNodeWithText("Owned reset VLESS").assertIsDisplayed()
            compose.onNodeWithText("Owned reset Hysteria").assertIsDisplayed()
            if (openActions) openProfileActionsFromList(profile.name)
            compose.onNodeWithText(text(R.string.vpn_close)).performClick()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithText(profile.name).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertDoesNotExist()
            compose.onNodeWithText("Owned reset Hysteria").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
            compose.onNodeWithText("Owned reset VLESS").assertIsDisplayed()
            compose.onNodeWithText("Owned reset Hysteria").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.vpn_close)).performClick()
        }
        compose.runOnIdle {
            assertEquals(profile.id, controller.state.value.selectedProfileId)
            assertEquals(VpnPhase.CONNECTED, controller.state.value.phase)
            assertEquals(17L, controller.state.value.connectionId)
            assertTrue(controller.state.value.hasOwnedTunnel)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
            assertTrue(controller.protocolSelections.isEmpty())
        }
    }

    @Test
    fun holdingCollapsedOrExpandedProfilesAlwaysCopiesTheWholeProfile() {
        val profile = OwnedProfile.copy(protocolOptions = listOf(
            VpnProtocolSummary("vless", "Owned VLESS", "VLESS"),
            VpnProtocolSummary("hy2", "Owned Hysteria", "HYSTERIA2"),
        ), selectedProtocolOptionId = "hy2", protocol = "HYSTERIA2")
        val controller = show(readyProfile().copy(profiles = listOf(profile)))
        for (expanded in listOf(false, true)) {
            compose.onNodeWithText(profile.name).performClick()
            if (expanded) {
                compose.onNodeWithContentDescription(context.getString(R.string.vpn_profile_expand_named, profile.name)).performClick()
                compose.onNodeWithText("Owned VLESS").assertIsDisplayed()
                compose.onNodeWithText("Owned Hysteria").assertIsDisplayed()
            }
            compose.onNodeWithText(text(R.string.vpn_profile_copy)).assertDoesNotExist()
            openProfileActionsFromList(profile.name)
            compose.onNodeWithText(text(R.string.vpn_profile_copy)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.vpn_close)).performClick()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
        }
        compose.runOnIdle {
            assertEquals(listOf(profile.id to null, profile.id to null), controller.exportRequests)
            assertEquals("hy2", controller.state.value.selectedProfile?.selectedProtocolOptionId)
            assertTrue(controller.protocolSelections.isEmpty())
        }
    }

    @Test
    fun settingsOffersFourThemesAndBackReturnsToTheSameHome() {
        val controller = show(readyProfile())
        compose.onNodeWithContentDescription(text(R.string.vpn_settings)).performClick()
        compose.onNodeWithText(text(R.string.vpn_settings)).assertIsDisplayed()
        assertNoRemovedSurfaces()

        chooseTheme(R.string.vpn_theme_foxhole)
        compose.runOnIdle {
            assertEquals(VpnTheme.DARK, controller.settings.value.theme)
            assertEquals(Color(0xFF0B0A08), observedBackground)
        }
        chooseTheme(R.string.vpn_theme_night)
        compose.runOnIdle {
            assertEquals(VpnTheme.NIGHT, controller.settings.value.theme)
            assertEquals(Color.Black, observedBackground)
        }
        chooseTheme(R.string.vpn_theme_light)
        compose.runOnIdle {
            assertEquals(VpnTheme.LIGHT, controller.settings.value.theme)
            assertEquals(Color(0xFFF2E8D5), observedBackground)
        }
        chooseTheme(R.string.vpn_theme_system)
        compose.runOnIdle {
            assertEquals(VpnTheme.SYSTEM, controller.settings.value.theme)
        }

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.onNodeWithText(text(R.string.vpn_start)).assertIsDisplayed()
        compose.onNodeWithText(OwnedProfile.name).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
        }
    }

    @Test
    fun settingsHasNoGlobeStylePicker() {
        show(readyProfile())
        compose.onNodeWithContentDescription(text(R.string.vpn_settings)).performClick()
        compose.onNodeWithText("Vector").assertDoesNotExist()
        compose.onNodeWithText("Вектор").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_theme)).assertExists()
    }

    private fun globeAction(label: Int) {
        val actions = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == text(label) }.action()) }
    }

    @Test
    fun modalActionsUseTheRegularFontAcrossImportProfilesSettingsAndConfirmation() {
        show(readyProfile().copy(profiles = listOf(OwnedProfile.copy(refreshable = true))))
        compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick()
        for (label in listOf(R.string.vpn_import_from_clipboard, R.string.vpn_import_file, R.string.vpn_import_manual)) {
            assertRegularFont(compose.onNodeWithText(text(label)))
        }
        compose.onNodeWithText(text(R.string.vpn_import_manual)).performClick()
        assertRegularFont(compose.onNodeWithText(text(R.string.vpn_import_clipboard)))
        assertRegularFont(compose.onNode(hasText(text(R.string.vpn_import)) and hasClickAction()))
        assertRegularFont(compose.onNodeWithText(text(R.string.vpn_cancel)))
        compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }

        compose.onNodeWithText(OwnedProfile.name).performClick()
        openProfileActionsFromList(OwnedProfile.name)
        assertRegularFont(compose.onNodeWithText(text(R.string.vpn_profile_copy)))
        assertRegularFont(compose.onNodeWithText(text(R.string.vpn_refresh)))
        val remove = compose.onNodeWithContentDescription(context.getString(R.string.vpn_remove_named, OwnedProfile.name))
        assertRegularFont(remove)
        remove.performClick()
        val confirmation = hasAnyAncestor(hasAnyDescendant(hasText(text(R.string.vpn_remove_title))))
        assertRegularFont(compose.onNode(hasText(text(R.string.vpn_continue)) and confirmation))
        compose.onNode(hasText(text(R.string.vpn_cancel)) and confirmation).performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }

        compose.onNodeWithContentDescription(text(R.string.vpn_settings)).performClick()
        compose.onNodeWithText(text(R.string.vpn_theme)).performScrollTo().performClick()
        assertRegularFont(compose.onNodeWithText(text(R.string.vpn_close)))
        assertRegularFont(compose.onNode(hasTextExactly(text(R.string.vpn_theme_system)) and hasClickAction() and hasAnyAncestor(isDialog())))
    }

    private fun assertRegularFont(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals(requireNotNull(regularFont), it.layoutInput.style.fontFamily) }
    }

    private fun openProfileActionsFromList(profileName: String) {
        val expand = hasContentDescription(context.getString(R.string.vpn_profile_expand_named, profileName))
        val collapse = hasContentDescription(context.getString(R.string.vpn_profile_collapse_named, profileName))
        compose.onNode(expand or collapse).performScrollTo().assertIsDisplayed().performTouchInput { longClick(center) }
        compose.onNodeWithText(text(R.string.vpn_profile_actions_title)).assertIsDisplayed()
        compose.onNode(hasText(profileName) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    private fun awaitGlobeReady() {
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        compose.waitUntil(10_000L) {
            !globe.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription)
        }
    }

    private fun show(initial: VpnUiState, importedText: String? = null, external: MutableState<String?>? = null): UiControllerFixture {
        val controller = UiControllerFixture(initial)
        compose.setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            VpnUiTheme(settings.theme) {
                val background = LocalVpnColors.current.background
                val regularFamily = MaterialTheme.typography.bodyMedium.fontFamily
                SideEffect {
                    observedBackground = background
                    regularFont = regularFamily
                }
                VpnApp(
                    controller, state, settings,
                    VpnUiActions(
                        connect = { permissionRequests++ },
                        chooseFile = { fileRequests++ },
                        systemSettings = { throw AssertionError("Unexpected external settings launch") },
                        consumeFile = { consumedInputs++ },
                        chooseQr = { qrRequests++ },
                        consumeError = { consumedErrors++; external?.value = null },
                    ),
                    importedFileText = importedText,
                    externalError = external?.value,
                )
            }
        }
        return controller
    }

    private fun assertNoticeAtTop(message: String) {
        val node = compose.onNodeWithText(message)
        node.assertIsDisplayed()
        val y = node.fetchSemanticsNode().positionOnScreen.y
        assertTrue(y >= 0 && y < context.resources.displayMetrics.heightPixels / 4f)
    }

    @Test fun startDuringStoppingIsDispatchedAndAQueuedStartCanBeCancelled() {
        val controller = show(readyProfile().copy(phase = VpnPhase.STOPPING, hasOwnedTunnel = true, canRestartDuringStop = true))
        compose.onNodeWithText(text(R.string.vpn_start)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(0, controller.connectCalls)
            assertEquals(0, controller.disconnectCalls)
            controller.state.value = controller.state.value.copy(hasPendingStart = true)
        }
        compose.onNodeWithText(text(R.string.vpn_stop)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, controller.disconnectCalls) }
    }

    private fun chooseTheme(label: Int) {
        compose.onNodeWithText(text(R.string.vpn_theme)).performScrollTo().performClick()
        val choices = hasTextExactly(text(R.string.vpn_theme_system)) or
            hasTextExactly(text(R.string.vpn_theme_foxhole)) or hasTextExactly(text(R.string.vpn_theme_light)) or hasTextExactly(text(R.string.vpn_theme_night))
        compose.onAllNodes(choices and hasAnyAncestor(isDialog())).assertCountEquals(4)
        val selectable = choices and hasClickAction() and hasAnyAncestor(isDialog())
        compose.onAllNodes(selectable).assertCountEquals(4)
        compose.onNodeWithText("OLED").assertDoesNotExist()
        compose.onNode(hasTextExactly(text(label)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
    }

    private fun assertNoRemovedSurfaces() {
        for (label in listOf("Tor", "TOR", "I2P", "Sentinel", "WebApps", "Map", "Карта", "Next", "Далее")) {
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    private fun text(id: Int): String = context.getString(id)

    private fun readyProfile() = VpnUiState(
        isReady = true,
        profiles = listOf(OwnedProfile),
        selectedProfileId = OwnedProfile.id,
    )

    private class UiControllerFixture(initial: VpnUiState) : VpnController {
        override val state = MutableStateFlow(initial)
        override val settings = MutableStateFlow(VpnSettings())
        var connectCalls = 0
        var disconnectCalls = 0
        val importConsents = mutableListOf<Boolean>()
        val protocolSelections = mutableListOf<Pair<String, String>>()
        val exportRequests = mutableListOf<Pair<String, Set<String>?>>()
        var selectionGate: CompletableDeferred<Unit>? = null
        var exportGate: CompletableDeferred<Unit>? = null
        var refreshGate: CompletableDeferred<Unit>? = null
        var refreshCalls = 0

        override suspend fun awaitReady() = Unit
        override fun connectSelected() { connectCalls++ }
        override fun disconnect() {
            disconnectCalls++
            state.value = state.value.copy(
                phase = VpnPhase.IDLE, hasOwnedTunnel = false, location = null, message = null,
            )
        }
        override fun selectProfile(id: String) { state.value = state.value.copy(selectedProfileId = id) }
        override fun refreshLocation() = Unit
        override suspend fun selectProtocol(profileId: String, optionId: String) {
            protocolSelections += profileId to optionId
            selectionGate?.await()
            state.value = state.value.copy(profiles = state.value.profiles.map { profile ->
                if (profile.id != profileId) profile else profile.copy(
                    protocol = profile.protocolOptions.single { it.id == optionId }.protocol,
                    selectedProtocolOptionId = optionId,
                )
            })
        }
        override suspend fun exportProfile(profileId: String, optionIds: Set<String>?): String {
            exportRequests += profileId to optionIds
            exportGate?.await()
            return "vless://00000000-0000-4000-8000-000000000001@8.8.8.8:443?security=tls#Owned"
        }
        override suspend fun importProfiles(text: String, allowInsecureTls: Boolean): VpnImportResult {
            assertTrue(text in setOf("owned-ui-profile", "owned-scanned-profile"))
            importConsents += allowInsecureTls
            return if (allowInsecureTls) VpnImportResult.Success(added = 1) else VpnImportResult.NeedsInsecureTlsConsent
        }
        override suspend fun refreshSubscription(profileId: String): VpnImportResult {
            val gate = refreshGate ?: throw AssertionError("Unexpected subscription request")
            refreshCalls++
            gate.await()
            return VpnImportResult.Success(added = 0, updated = 1)
        }
        override suspend fun removeProfile(id: String) {
            state.value = state.value.copy(profiles = state.value.profiles.filterNot { it.id == id })
        }
        override suspend fun updateSettings(settings: VpnSettings) { this.settings.value = settings }
    }

    private companion object {
        val OwnedProfile = VpnProfileSummary("owned-ui-profile", "Owned UI profile", "VLESS")
        val OwnedLocation = VpnLocation("NL", "Owned exit city", "Owned exit country", "192.0.2.19", 52.0, 4.0)
    }
}
