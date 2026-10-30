package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnGlobeRestorationAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manualOrientationAndDismissedIntroSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val state = mutableStateOf(readyState())
        restoration.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnHome(
                    state.value,
                    onPrimary = { throw AssertionError("Unexpected VPN command") },
                    onProfiles = { throw AssertionError("Unexpected profile picker") },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        awaitGlobeReady()
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        compose.onNodeWithText(Device.city).assertIsDisplayed()
        globe.performClick()
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        val initial = globe.captureToImage().asAndroidBitmap()
        globe.performTouchInput {
            swipe(Offset(width * .4f, height * .55f), Offset(width * .65f, height * .6f), 300L)
        }
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        val moved = globe.captureToImage().asAndroidBitmap()
        assertFalse(initial.sameAs(moved))

        restoration.emulateSavedInstanceStateRestore()
        awaitGlobeReady()

        compose.onNodeWithText(Device.city).assertDoesNotExist()
        assertTrue("manual globe orientation must survive restoration", moved.sameAs(globe.captureToImage().asAndroidBitmap()))
    }

    @Test
    fun routeChangeCancelsFingerDownWithoutSynthesizingATapOrRecentering() {
        val state = mutableStateOf(readyState())
        show(state)
        awaitGlobeReady()
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        globe.performClick()
        awaitGlobeReady()
        val actions = globe.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.single { it.label == text(R.string.vpn_globe_right) }.action())
        }
        awaitGlobeReady()
        compose.onNodeWithText(Device.city).assertDoesNotExist()
        val before = globe.captureToImage().asAndroidBitmap()

        globe.performTouchInput { down(Offset(width * .35f, height * .5f)) }
        compose.runOnIdle {
            val current = state.value.deviceLocation
            // Change the route key without changing its float geometry, isolating a phantom recenter.
            state.value = state.value.copy(
                deviceLocation = current.copy(
                    location = requireNotNull(current.location).copy(longitude = requireNotNull(Device.longitude) + 1e-9),
                ),
            )
        }
        compose.waitForIdle()
        globe.performTouchInput { up() }
        awaitGlobeReady()

        compose.onNodeWithText(Device.city).assertDoesNotExist()
        assertTrue("a cancelled finger-down is not a request to center the globe", before.sameAs(globe.captureToImage().asAndroidBitmap()))
    }

    private fun show(state: MutableState<VpnUiState>) {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnHome(
                    state.value,
                    onPrimary = { throw AssertionError("Unexpected VPN command") },
                    onProfiles = { throw AssertionError("Unexpected profile picker") },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun awaitGlobeReady() {
        val globe = compose.onNodeWithContentDescription(text(R.string.vpn_globe))
        compose.waitUntil(5_000) {
            !globe.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription)
        }
        compose.waitForIdle()
    }

    private fun text(id: Int): String = context.getString(id)

    private fun readyState() = VpnUiState(
        isReady = true,
        profiles = listOf(Profile),
        selectedProfileId = Profile.id,
        deviceLocation = VpnDeviceLocation(Device, DeviceLocationStatus.CURRENT),
    )

    private companion object {
        val Profile = VpnProfileSummary("owned-restoration-profile", "Owned profile", "VLESS")
        val Device = VpnLocation("AU", "Owned device city", "Owned country", "192.0.2.8", -33.0, 151.0)
    }
}
