package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnPhase
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnGlobeCalloutAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmedServerOpensAutomaticallyAndManualRecenteringAndDeviceSelectionHideIt() {
        val state = mutableStateOf(Scene())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SceneContent(state) }
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(connected = true) }
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(exit = Server) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Device.city).assertDoesNotExist()

        action(R.string.vpn_globe_right, repeats = 9)
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        tapBlankGlobe()
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        action(R.string.vpn_globe_device)
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        tapBlankGlobe()
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        action(R.string.vpn_globe_server)

        restoration.emulateSavedInstanceStateRestore()
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
    }

    @Test
    fun connectedDeviceAndServerPointsOpenTheirOwnExclusiveCards() {
        val state = mutableStateOf(Scene(connected = true, exit = Server, connectionId = 17L))
        compose.setContent { SceneContent(state) }
        awaitGlobeReady()
        val route = GlobeRoute(GlobeCoordinates.from(Device.latitude, Device.longitude), GlobeCoordinates.from(Server.latitude, Server.longitude))
        fun tapPoint(point: FloatArray) {
            val rotated = GlobeProjection(requireNotNull(route.center)).rotate(point)
            globe().performTouchInput {
                click(Offset(width * (200f + 145f * rotated[0]) / 400f, width * (193f - 145f * rotated[1]) / 400f))
            }
        }
        tapPoint(requireNotNull(route.device))
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connected = false, phase = VpnPhase.ERROR) }
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connected = true, phase = null) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        tapPoint(requireNotNull(route.device))
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        tapPoint(requireNotNull(route.exit))
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
    }

    @Test
    fun coincidentGeoLocationsKeepSeparateTappableDeviceAndServerPoints() {
        val state = mutableStateOf(Scene(connected = true,
            exit = Server.copy(latitude = Device.latitude, longitude = Device.longitude), connectionId = 17L))
        compose.setContent { SceneContent(state) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        val route = GlobeRoute(GlobeCoordinates.from(Device.latitude, Device.longitude), GlobeCoordinates.from(Device.latitude, Device.longitude))
        val geometry = GlobeRouteGeometry(route).apply { update(requireNotNull(route.center)) }
        fun tap(point: Offset) {
            globe().performTouchInput { click(Offset(width * point.x / 400f, width * point.y / 400f)) }
        }
        tap(requireNotNull(geometry.device))
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        tap(requireNotNull(geometry.exit))
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        tap(requireNotNull(geometry.device))
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
    }

    @Test
    fun disconnectReturnsHomeAndInvalidConfirmationHidesRetainedServerMetadata() {
        val state = mutableStateOf(Scene(connected = true, exit = Server))
        compose.setContent { SceneContent(state) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()

        compose.runOnIdle { state.value = state.value.copy(connected = false) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(exit = Server.copy(city = "Changed retained server")) }
        compose.onNodeWithText("Changed retained server").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(connected = true, exit = Server.copy(ip = "")) }
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(exit = Server) }
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(exit = null) }
        compose.onNodeWithText(Server.city).assertDoesNotExist()
    }

    @Test
    fun tappingTheServerCardKeepsItHiddenThroughRotationBackgroundTapAndRestorationUntilExplicitReopen() {
        val state = mutableStateOf(Scene(connected = true, exit = Server, connectionId = 17L))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SceneContent(state) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).performClick()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        action(R.string.vpn_globe_right, repeats = 9)
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        tapBlankGlobe()
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        action(R.string.vpn_globe_server)
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Server.city).performClick()
        val route = GlobeRoute(GlobeCoordinates.from(Device.latitude, Device.longitude), GlobeCoordinates.from(Server.latitude, Server.longitude))
        val point = GlobeProjection(requireNotNull(route.center)).rotate(requireNotNull(route.exit))
        globe().performTouchInput {
            click(Offset(width * (200f + 145f * point[0]) / 400f, width * (193f - 145f * point[1]) / 400f))
        }
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
    }

    @Test
    fun aNewValidatedConnectionReopensTheCardButRetainedErrorAndSameConnectionDoNot() {
        val state = mutableStateOf(Scene(connected = true, exit = Server, connectionId = 17L))
        compose.setContent { SceneContent(state) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).performClick()
        compose.runOnIdle { state.value = state.value.copy(connected = false, phase = VpnPhase.ERROR, exit = Server.copy(city = "Retained error metadata")) }
        compose.onNodeWithText("Retained error metadata").assertDoesNotExist()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connected = true, phase = null, exit = Server) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connectionId = 18L) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
    }

    @Test fun startingConnectionHidesHomeAndStoppingReturnsItAfterIdle() {
        val state = mutableStateOf(Scene())
        compose.setContent { SceneContent(state) }
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        for (phase in listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING)) {
            compose.runOnIdle { state.value = state.value.copy(phase = phase) }
            compose.onNodeWithText(Device.city).assertDoesNotExist()
            compose.onNodeWithText(Server.city).assertDoesNotExist()
        }
        compose.runOnIdle { state.value = Scene(connected = true, exit = Server, connectionId = 7L) }
        awaitGlobeReady()
        compose.onNodeWithText(Server.city).assertIsDisplayed()
        compose.runOnIdle { state.value = Scene(phase = VpnPhase.STOPPING, connectionId = 7L) }
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.runOnIdle { state.value = Scene() }
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        compose.onNodeWithText(Server.city).assertDoesNotExist()
    }

    @Composable
    private fun SceneContent(state: MutableState<Scene>) {
        val scene = state.value
        val route = remember(scene.connected, scene.exit?.latitude, scene.exit?.longitude) {
            GlobeRoute(
                GlobeCoordinates.from(Device.latitude, Device.longitude),
                GlobeCoordinates.from(scene.exit?.latitude.takeIf { scene.connected }, scene.exit?.longitude.takeIf { scene.connected }),
            )
        }
        VpnUiTheme(VpnTheme.DARK) {
            VpnPlanet(route, scene.connected, Device, scene.exit, Modifier.width(300.dp), connectionId = scene.connectionId, phase = scene.phase ?: if (scene.connected) VpnPhase.CONNECTED else VpnPhase.IDLE)
        }
    }

    private fun action(id: Int, repeats: Int = 1) {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
        val actions = globe().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { repeat(repeats) { assertTrue(actions.single { it.label == label }.action()) } }
    }

    private fun globe() = compose.onNodeWithContentDescription(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_globe),
    )

    private fun tapBlankGlobe() {
        val bounds = globe().fetchSemanticsNode().boundsInRoot
        val point = Offset(bounds.width * .5f, bounds.height * .76f)
        val center = Offset(bounds.width * .5f, bounds.width * 193f / 400f)
        assertTrue((point - center).getDistance() < bounds.width * 145f / 400f)
        // These fixture points stay on the equator; the lower hemisphere is outside both 48dp targets.
        assertTrue(point.y - center.y > 24f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)
        for (text in listOf(Device.city, Server.city)) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().forEach { card ->
                assertFalse(card.boundsInRoot.contains(bounds.topLeft + point))
            }
        }
        globe().performTouchInput { click(Offset(width * .5f, height * .76f)) }
    }

    private fun awaitGlobeReady() {
        compose.waitUntil(10_000) {
            !globe().fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription)
        }
        compose.waitForIdle()
    }

    private data class Scene(val connected: Boolean = false, val exit: VpnLocation? = null, val connectionId: Long? = null, val phase: VpnPhase? = null)

    private companion object {
        val Device = VpnLocation("US", "Owned device city", "Owned device country", "192.0.2.8", 0.0, 0.0)
        val Server = VpnLocation("GB", "Owned server city", "Owned server country", "203.0.113.8", 0.0, 20.0)
    }
}
