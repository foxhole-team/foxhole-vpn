package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnGlobeLongDragAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun northernHorizontalDragContinuesBeyondTheOldAnchorLimit() {
        show(75.0)
        val globe = compose.onNodeWithContentDescription(description())
        globe.performTouchInput {
            down(Offset(width * .5f, height * .48f))
            moveTo(Offset(width * .74f, height * .48f), 200)
        }
        val first = globe.captureToImage().asAndroidBitmap()
        globe.performTouchInput { moveTo(Offset(width * .88f, height * .48f), 200) }
        assertFalse("a held finger must keep turning the globe beyond the old high-latitude limit",
            first.sameAs(globe.captureToImage().asAndroidBitmap()))
        globe.performTouchInput { up() }
        awaitReady()
    }

    @Test fun diagonalDragCanReverseImmediatelyAfterLeavingTheSphere() {
        show(55.0)
        val globe = compose.onNodeWithContentDescription(description())
        globe.performTouchInput {
            down(Offset(width * .5f, height * .48f))
            moveTo(Offset(width * .9f, height * .8f), 250)
        }
        val edge = globe.captureToImage().asAndroidBitmap()
        globe.performTouchInput { moveTo(Offset(width * .7f, height * .6f), 200) }
        assertFalse("reversal must not wait for the finger to return to its original anchor",
            edge.sameAs(globe.captureToImage().asAndroidBitmap()))
        globe.performTouchInput { up() }
        awaitReady()
    }

    private fun show(latitude: Double) {
        val home = VpnLocation(countryCode = "NO", city = "", country = "Norway", ip = "203.0.113.1", latitude = latitude, longitude = 10.0)
        val route = GlobeRoute(GlobeCoordinates.from(latitude, 10.0), null)
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) { VpnPlanet(route, false, home, null, Modifier.width(300.dp)) }
        }
        awaitReady()
    }

    private fun awaitReady() {
        val globe = compose.onNodeWithContentDescription(description())
        compose.waitUntil(10_000) { !globe.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription) }
    }

    private fun description() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_globe)
}
