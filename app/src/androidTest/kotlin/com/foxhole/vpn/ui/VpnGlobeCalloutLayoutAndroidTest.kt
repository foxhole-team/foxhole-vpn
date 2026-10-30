package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnGlobeCalloutLayoutAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun norwayToLatviaKeepsTheHomePointAndIncomingLineAboveTheServerCard() {
        assertClearRoute(Norway, Latvia, below = true)
    }

    @Test fun latviaToNorwayKeepsTheHomePointAndIncomingLineBelowTheServerCard() {
        assertClearRoute(Latvia, Norway, below = false)
    }

    @Test fun anEquatorialRouteKeepsItsPointsAndLineOutsideTheAboveCard() {
        assertClearRoute(Norway.copy(latitude = 0.0, longitude = 0.0), Latvia.copy(latitude = 0.0, longitude = 20.0), below = false)
    }

    private fun assertClearRoute(home: VpnLocation, server: VpnLocation, below: Boolean) {
        val route = GlobeRoute(GlobeCoordinates.from(home.latitude, home.longitude), GlobeCoordinates.from(server.latitude, server.longitude))
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnPlanet(route, true, home, server, Modifier.width(300.dp), connectionId = 17L)
            }
        }
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_globe)
        val globe = compose.onNodeWithContentDescription(description)
        compose.waitUntil(10_000) { !globe.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription) }
        compose.waitForIdle()
        compose.onNodeWithText(server.country).assertIsDisplayed()
        compose.onNodeWithText(server.city).assertIsDisplayed()
        val card = compose.onNode(hasText(server.city) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val viewport = globe.fetchSemanticsNode().boundsInRoot
        val scale = viewport.width / 400f
        val geometry = GlobeRouteGeometry(route).apply { update(requireNotNull(route.center)) }
        fun inRoot(point: Offset) = viewport.topLeft + point * scale
        val homePoint = inRoot(requireNotNull(geometry.device))
        val serverPoint = inRoot(requireNotNull(geometry.exit))
        assertFalse("Server callout must not cover the home marker", card.contains(homePoint))
        assertFalse("Callout pointer must leave a gap around the server marker", card.contains(serverPoint))
        if (below) assertTrue(card.top > serverPoint.y) else assertTrue(card.bottom < serverPoint.y)
        assertTrue(card.left >= viewport.left && card.right <= viewport.right)
        assertTrue(card.top >= viewport.top && card.bottom <= viewport.bottom)
        val projected = GlobeProjection(requireNotNull(route.center)).rotate(route.arc)
        for (index in projected.indices step 3) {
            if (projected[index + 2] > .02f) {
                val point = inRoot(Offset(200f + 145f * projected[index], 193f - 145f * projected[index + 1]))
                assertFalse("Server callout must not cover the connection line", card.contains(point))
            }
        }
    }

    private companion object {
        val Norway = VpnLocation("NO", "Vesteralen", "Norway", "192.0.2.8", 68.7, 15.4)
        val Latvia = VpnLocation("LV", "Riga", "Latvia", "203.0.113.8", 56.9, 24.1)
    }
}
