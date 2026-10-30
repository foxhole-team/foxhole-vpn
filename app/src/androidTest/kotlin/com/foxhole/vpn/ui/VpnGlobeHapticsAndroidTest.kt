package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnGlobeHapticsAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dragTicksStopBeforeOneManualCenterTickAndIdle() {
        val requests = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                requests += hapticFeedbackType
            }
        }
        val route = GlobeRoute(requireNotNull(GlobeCoordinates.from(0.0, 0.0)), null)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                VpnUiTheme(VpnTheme.DARK) {
                    VpnPlanet(route, false, null, null, Modifier.width(300.dp))
                }
            }
        }
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_globe)
        val globe = compose.onNodeWithContentDescription(description)
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue(requests.isEmpty()) }

        globe.performTouchInput {
            swipe(Offset(width * .4f, height * .48f), Offset(width * .44f, height * .48f), 400)
        }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }

        globe.performTouchInput {
            swipe(Offset(width * .4f, height * .48f), Offset(width * .55f, height * .48f), 400)
        }
        var afterDrag = 0
        compose.runOnIdle {
            assertTrue(requests.size in 1..2)
            assertTrue(requests.all { it == HapticFeedbackType.SegmentTick })
            afterDrag = requests.size
        }
        compose.mainClock.advanceTimeBy(5_000)
        globe.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(afterDrag + 1, requests.size); assertEquals(HapticFeedbackType.GestureEnd, requests.last()) }
        compose.waitUntil(10_000) {
            !globe.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription)
        }
        globe.performTouchInput {
            swipe(Offset(width * .55f, height * .48f), Offset(width * .4f, height * .48f), 400)
        }
        var afterSecondDrag = 0
        compose.runOnIdle {
            assertTrue(requests.size - afterDrag - 1 in 1..2)
            assertEquals(1, requests.count { it == HapticFeedbackType.GestureEnd })
            assertTrue(requests.filter { it != HapticFeedbackType.GestureEnd }.all { it == HapticFeedbackType.SegmentTick })
            afterSecondDrag = requests.size
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(afterSecondDrag, requests.size) }
    }

    @Test
    fun aBlankManualTapHidesTheServerAndTicksOnceWithoutBackgroundOrCompletionReplay() {
        val requests = mutableListOf<HapticFeedbackType>()
        val haptics = feedback(requests)
        lateinit var owner: CueLifecycleOwner
        compose.runOnUiThread { owner = CueLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        val route = GlobeRoute(GlobeCoordinates.from(0.0, 0.0), GlobeCoordinates.from(Server.latitude, Server.longitude))
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalLifecycleOwner provides owner) {
                VpnUiTheme(VpnTheme.DARK) { VpnPlanet(route, true, null, Server, Modifier.width(300.dp)) }
            }
        }
        val globe = compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_globe))
        compose.waitUntil(10_000) { !globe.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription) }
        globe.performTouchInput { click(Offset(width * .5f, height * .72f)) }
        compose.onNodeWithText(Server.city).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.GestureEnd), requests); owner.registry.currentState = Lifecycle.State.CREATED }
        globe.performSemanticsAction(SemanticsActions.OnClick) { assertFalse(it()) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.GestureEnd), requests) }
    }

    @Test
    fun validatedConnectionConfirmsOnceAfterCenteringAndLateGeo() {
        val requests = mutableListOf<HapticFeedbackType>()
        val haptics = feedback(requests)
        val claims = mutableListOf<Long>()
        val claim: (Long) -> Boolean = { claims += it; true }
        val connected = mutableStateOf(false)
        val exit = mutableStateOf<VpnLocation?>(null)
        val id = mutableStateOf<Long?>(1)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                VpnUiTheme(VpnTheme.DARK) {
                    val route = remember(exit.value?.latitude, exit.value?.longitude) {
                        GlobeRoute(GlobeCoordinates.from(0.0, 0.0), GlobeCoordinates.from(exit.value?.latitude, exit.value?.longitude))
                    }
                    VpnPlanet(route, connected.value, null, exit.value, Modifier.width(300.dp), connectionId = id.value, claimConnectionCue = claim)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { connected.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertTrue(requests.isEmpty()); exit.value = Server }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.Confirm), requests)
            assertEquals(listOf(1L), claims)
        }

        compose.runOnIdle { connected.value = false; exit.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { connected.value = true; exit.value = Server.copy(ip = "203.0.113.9") }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm), requests) }
    }

    @Test
    fun touchValidationLossBackgroundAndRecreationDoNotQueueAConfirmation() {
        val requests = mutableListOf<HapticFeedbackType>()
        val haptics = feedback(requests)
        val claimed = mutableSetOf<Long>()
        val claimRequests = mutableListOf<Long>()
        val claim: (Long) -> Boolean = { claimRequests += it; claimed.add(it) }
        val scene = mutableStateOf(0)
        val connected = mutableStateOf(false)
        val exit = mutableStateOf<VpnLocation?>(null)
        val id = mutableStateOf<Long?>(null)
        lateinit var owner: CueLifecycleOwner
        compose.runOnUiThread { owner = CueLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalLifecycleOwner provides owner) {
                VpnUiTheme(VpnTheme.DARK) {
                    val route = remember(exit.value?.latitude, exit.value?.longitude) {
                        GlobeRoute(GlobeCoordinates.from(0.0, 0.0), GlobeCoordinates.from(exit.value?.latitude, exit.value?.longitude))
                    }
                    key(scene.value) {
                        VpnPlanet(route, connected.value, null, exit.value, Modifier.width(300.dp), connectionId = id.value, claimConnectionCue = claim)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.vpn_globe)
        val globe = compose.onNodeWithContentDescription(description)

        compose.runOnIdle { id.value = 1; connected.value = true; exit.value = Server }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        val target = requireNotNull(GlobeRoute(GlobeCoordinates.from(0.0, 0.0), GlobeCoordinates.from(Server.latitude, Server.longitude)).center)
        val node = globe.fetchSemanticsNode().boundsInRoot
        val blank = Offset(node.width * .5f, node.height * .76f)
        val sphereCenter = Offset(node.width * .5f, node.width * 193f / 400f)
        assertTrue((blank - sphereCenter).getDistance() < node.width * 145f / 400f)
        val hitRadius = 24f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        for (step in 0..20) {
            val projection = GlobeProjection(GlobeOrientation().toward(target, step / 20f))
            for (coordinate in listOf(GlobeCoordinates.from(0.0, 0.0), GlobeCoordinates.from(Server.latitude, Server.longitude))) {
                val point = projection.rotate(requireNotNull(coordinate).vector())
                if (globePointVisible(point)) {
                    val projected = Offset(node.width * (200f + 145f * point[0]) / 400f, node.width * (193f - 145f * point[1]) / 400f)
                    assertTrue((blank - projected).getDistance() > hitRadius)
                }
            }
        }
        assertFalse(compose.onNodeWithText(Server.city).fetchSemanticsNode().boundsInRoot.contains(node.topLeft + blank))
        globe.performTouchInput { down(Offset(width * .5f, height * .76f)) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals("Held touch must cancel the connection confirmation", emptyList<HapticFeedbackType>(), requests) }
        globe.performTouchInput { up() }
        val manualTick = listOf(HapticFeedbackType.GestureEnd)
        compose.runOnIdle { assertEquals("A real blank tap emits exactly its own tick", manualTick, requests) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals("Recentering must not queue connection feedback", manualTick, requests) }

        compose.runOnIdle { id.value = 2 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertEquals(manualTick, requests); connected.value = false; exit.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals("Validation loss must not emit Confirm", manualTick, requests); connected.value = true; exit.value = Server }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(manualTick, requests) }

        compose.runOnIdle { id.value = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals("Background return must not queue Confirm", manualTick, requests); scene.value++ }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertEquals("Recreation must preserve the claimed connection cue", manualTick, requests)
            assertEquals(listOf(1L, 2L, 3L, 3L), claimRequests)
        }
    }

    private fun feedback(requests: MutableList<HapticFeedbackType>) = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            requests += hapticFeedbackType
        }
    }

    private class CueLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        val Server = VpnLocation("DE", "Server city", "Germany", "203.0.113.8", 52.0, 13.0)
    }
}
