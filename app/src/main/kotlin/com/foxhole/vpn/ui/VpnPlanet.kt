package com.foxhole.vpn.ui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun VpnPlanet(
    route: GlobeRoute,
    connected: Boolean,
    deviceLocation: VpnLocation?,
    exitLocation: VpnLocation?,
    modifier: Modifier = Modifier,
    connectionId: Long? = null,
    claimConnectionCue: (Long) -> Boolean = { true },
    phase: VpnPhase = if (connected) VpnPhase.CONNECTED else VpnPhase.IDLE,
) {
    val geometry by produceState<PreparedGlobe?>(null) {
        value = withContext(Dispatchers.Default) { PreparedGlobe(Build.VERSION.SDK_INT < 29) }
    }
    val orientation = rememberSaveable(saver = GlobeOrientationSaver) { mutableStateOf(GlobeOrientation()) }
    val settledOrientation = remember { mutableStateOf(orientation.value) }
    val moving = remember { mutableStateOf(false) }
    val fullReady = remember { mutableStateOf<GlobeOrientation?>(null) }
    val motion = remember { GlobeMotionJob() }
    val dragHaptics = remember { GlobeDragHaptics() }
    val hapticFeedback = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val confirmation = validatedGlobeExit(connected, exitLocation) && route.exit != null
    val currentConfirmation = rememberUpdatedState(confirmation)
    val currentConnectionId = rememberUpdatedState(connectionId)
    val connectionCue = rememberSaveable(saver = GlobeConnectionCueSaver) { GlobeConnectionCue() }
    val arcReveal = remember { mutableFloatStateOf(0f) }
    val touchActive = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var centerRequest by remember { mutableIntStateOf(0) }
    var calloutCue by rememberSaveable(saver = GlobeCalloutCueSaver) { mutableStateOf(GlobeCalloutCue()) }
    var previousPhase by rememberSaveable { mutableStateOf(phase.name) }
    val callout = if (phase == VpnPhase.IDLE || phase == VpnPhase.CONNECTED) calloutCue.visible(confirmation) else GlobeCallout.NONE

    fun cancelMotion(): Int {
        val connectionActive = motion.connectionActive
        val ticket = motion.cancel()
        if (connectionActive) arcReveal.floatValue = if (currentConfirmation.value) 1f else 0f
        return ticket
    }

    fun motionAllowed(): Boolean = globeMotionAllowed(
        scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f,
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
    )

    fun requestCenter(): Boolean {
        if (route.center == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        cancelMotion()
        moving.value = false
        settledOrientation.value = orientation.value
        calloutCue = calloutCue.recenter(currentConfirmation.value)
        centerRequest++
        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
        return true
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                cancelMotion()
                settledOrientation.value = orientation.value
                moving.value = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancelMotion()
        }
    }
    LaunchedEffect(phase, connectionId, confirmation) {
        val returnedHome = phase == VpnPhase.IDLE && previousPhase != VpnPhase.IDLE.name
        previousPhase = phase.name
        calloutCue = calloutCue.phaseChanged(phase, returnedHome, route.device != null)
        if (returnedHome && route.center != null) centerRequest++
        calloutCue = calloutCue.connectionChanged(confirmation, connectionId)
        val start = connectionCue.beginIfNew(connectionId, confirmation)
        val claimed = start && connectionId != null && claimConnectionCue(connectionId)
        if (!confirmation) {
            if (motion.connectionActive) {
                cancelMotion()
                settledOrientation.value = orientation.value
                moving.value = false
            }
            arcReveal.floatValue = 0f
            return@LaunchedEffect
        }
        calloutCue = calloutCue.locationReady(confirmed = true, hasDevice = route.device != null)
        if (!claimed || touchActive.value || !motionAllowed()) {
            arcReveal.floatValue = 1f
            return@LaunchedEffect
        }
        val target = route.center ?: return@LaunchedEffect
        val ticket = motion.startConnection()
        val from = orientation.value
        arcReveal.floatValue = 0f
        moving.value = true
        motion.job = scope.launch {
            try {
                animate(0f, 1f, animationSpec = GlobeConnectionSpec) { fraction, _ ->
                    if (motion.generation == ticket) {
                        orientation.value = from.toward(target, fraction)
                        arcReveal.floatValue = fraction
                    }
                }
                withFrameNanos { }
                if (motion.generation == ticket && currentConfirmation.value && currentConnectionId.value == connectionId &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            } finally {
                if (motion.generation == ticket) {
                    motion.finishConnection()
                    settledOrientation.value = orientation.value
                    moving.value = false
                }
            }
        }
    }
    val settled = settledOrientation.value
    LaunchedEffect(geometry, settled) {
        val prepared = geometry ?: return@LaunchedEffect
        fullReady.value = null
        withContext(Dispatchers.Default) {
            prepared.updateLock.withLock { prepared.full.update(settled) }
        }
        fullReady.value = settled
    }
    LaunchedEffect(route.device?.contentHashCode(), confirmation, phase) {
        val coordinates = deviceLocation?.takeIf { phase == VpnPhase.IDLE }?.let { GlobeCoordinates.from(it.latitude, it.longitude) }
        val openIntro = calloutCue.introPending && coordinates != null && !confirmation
        calloutCue = calloutCue.locationReady(confirmation, coordinates != null)
        if (openIntro && coordinates != null) {
            orientation.value = GlobeOrientation.centeredOn(coordinates)
            settledOrientation.value = orientation.value
        }
    }
    LaunchedEffect(centerRequest) {
        val target = route.center
        if (centerRequest == 0 || target == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val ticket = cancelMotion()
        val from = orientation.value
        if (!motionAllowed()) {
            orientation.value = target
            settledOrientation.value = target
            moving.value = false
            return@LaunchedEffect
        }
        moving.value = true
        motion.job = scope.launch {
            try {
                animate(0f, 1f, animationSpec = GlobeRecenterSpec) { fraction, _ ->
                    if (motion.generation == ticket) orientation.value = from.toward(target, fraction)
                }
            } finally {
                if (motion.generation == ticket) {
                    settledOrientation.value = orientation.value
                    moving.value = false
                }
            }
        }
    }

    fun turn(horizontal: Float, vertical: Float): Boolean {
        cancelMotion()
        moving.value = false
        orientation.value = orientation.value.turn(horizontal, vertical)
        settledOrientation.value = orientation.value
        return true
    }
    val routeGeometry = remember(route) { GlobeRouteGeometry(route) }
    val colors = LocalVpnColors.current
    val brushes = remember(colors.light, colors.monochrome, colors.globeLandTint, colors.globeDeviceTint, colors.globeServerTint) {
        GlobeAppearance(colors.light, colors.monochrome, colors.globeLandTint, colors.globeDeviceTint, colors.globeServerTint)
    }
    val description = stringResource(R.string.vpn_globe)
    val loading = stringResource(R.string.vpn_globe_loading)
    val left = stringResource(R.string.vpn_globe_left)
    val right = stringResource(R.string.vpn_globe_right)
    val up = stringResource(R.string.vpn_globe_up)
    val down = stringResource(R.string.vpn_globe_down)
    val reset = stringResource(R.string.vpn_globe_reset)
    val centerAction = stringResource(R.string.vpn_globe_center)
    val deviceAction = stringResource(R.string.vpn_globe_device)
    val exitAction = stringResource(R.string.vpn_globe_server)
    var canvasWidth by remember { mutableIntStateOf(0) }
    Box(modifier.aspectRatio(400f / 410f).onSizeChanged { canvasWidth = it.width }.graphicsLayer {
        // Cache the settled globe while unrelated UI animations request frames.
        compositingStrategy = if (moving.value) CompositingStrategy.Auto else CompositingStrategy.Offscreen
    }) {
    Canvas(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer()
            .pointerInput(route, hapticFeedback) {
                fun rotate(amount: Offset, eventMillis: Long) {
                    if (size.width <= 0) return
                    val previous = orientation.value
                    val current = globeDragOrientation(previous, amount.x, amount.y, size.width)
                    orientation.value = current
                    if (dragHaptics.onDrag(previous, current, eventMillis)) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width * .5f, size.width * 193f / 400f)
                    if ((first.position - center).getDistance() > size.width * 145f / 400f) return@awaitEachGesture
                    val gestureTicket = cancelMotion()
                    touchActive.value = true
                    moving.value = false
                    settledOrientation.value = orientation.value
                    first.consume()
                    calloutCue = calloutCue.touchStarted()
                    val tracker = VelocityTracker()
                    var trackedPointer = first.id
                    fun track(change: PointerInputChange) {
                        if (change.id != trackedPointer) {
                            tracker.resetTracking()
                            trackedPointer = change.id
                        }
                        tracker.addPointerInputChange(change)
                    }
                    track(first)
                    var moved = false
                    var released = false
                    var completed = false
                    try {
                        val start = awaitTouchSlopOrCancellation(first.id) { change, amount ->
                            if (motion.generation == gestureTicket) {
                                moved = true
                                calloutCue = calloutCue.dismissDevice()
                                moving.value = true
                                dragHaptics.beginDrag()
                                track(change)
                                rotate(amount, change.uptimeMillis)
                            }
                            change.consume()
                        }
                        if (start != null) {
                            released = drag(start.id) { change ->
                                if (motion.generation == gestureTicket) {
                                    track(change)
                                    rotate(change.positionChange(), change.uptimeMillis)
                                }
                                change.consume()
                            }
                            if (released) {
                                currentEvent.changes.firstOrNull { it.id == trackedPointer && !it.pressed }?.let { track(it) }
                            }
                        }
                        completed = true
                    } finally {
                        touchActive.value = false
                        dragHaptics.endDrag()
                        if (!completed && motion.generation == gestureTicket) {
                            settledOrientation.value = orientation.value
                            moving.value = false
                        }
                        if (completed && !moved && motion.generation == gestureTicket && currentEvent.changes.any { it.id == first.id && !it.pressed }) {
                            routeGeometry.update(orientation.value, if (currentConfirmation.value) arcReveal.floatValue else 0f)
                            val position = first.position * (400f / size.width)
                            val hitRadius = 24.dp.toPx() * 400f / size.width
                            val hit = globeMarkerHit(routeGeometry.device, routeGeometry.exit.takeIf { currentConfirmation.value }, position,
                                hitRadius, 2.dp.toPx() * 400f / size.width, calloutCue.visible(currentConfirmation.value))
                            when (hit) {
                                GlobeCallout.SERVER -> calloutCue = calloutCue.showServer(currentConfirmation.value)
                                GlobeCallout.DEVICE -> calloutCue = calloutCue.toggleDevice(currentConfirmation.value)
                                GlobeCallout.NONE -> requestCenter()
                            }
                        }
                        if (completed && moved && motion.generation == gestureTicket) {
                            val ticket = cancelMotion()
                            val velocity = if (released) tracker.calculateVelocity() else androidx.compose.ui.unit.Velocity.Zero
                            val from = orientation.value
                            val fling = if (motionAllowed()) globeFlingMotion(velocity.x, velocity.y, size.width) else null
                            if (fling == null) {
                                settledOrientation.value = from
                                moving.value = false
                            } else motion.job = scope.launch {
                                try {
                                    animateDecay(0f, fling.initialSpeed, fling.decay) { distance, _ ->
                                        if (motion.generation == ticket) {
                                            if (motionAllowed()) orientation.value = fling.orientationAt(from, distance)
                                            else {
                                                cancelMotion()
                                                settledOrientation.value = orientation.value
                                                moving.value = false
                                            }
                                        }
                                    }
                                } finally {
                                    if (motion.generation == ticket) {
                                        settledOrientation.value = orientation.value
                                        moving.value = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .semantics {
                contentDescription = description
                role = Role.Image
                onClick(centerAction) { requestCenter() }
                if (geometry == null || moving.value || fullReady.value != settledOrientation.value) stateDescription = loading
                customActions = listOf(
                    CustomAccessibilityAction(left) { turn(-20f, 0f) },
                    CustomAccessibilityAction(right) { turn(20f, 0f) },
                    CustomAccessibilityAction(up) { turn(0f, -20f) },
                    CustomAccessibilityAction(down) { turn(0f, 20f) },
                    CustomAccessibilityAction(deviceAction) {
                        if (route.device == null) false else { calloutCue = calloutCue.showDevice(confirmation); true }
                    },
                    CustomAccessibilityAction(exitAction) {
                        if (!confirmation || route.exit == null) false else { calloutCue = calloutCue.showServer(true); true }
                    },
                    CustomAccessibilityAction(reset) {
                        cancelMotion()
                        moving.value = false
                        calloutCue = calloutCue.dismissDevice()
                        orientation.value = GlobeOrientation()
                        settledOrientation.value = orientation.value
                        true
                    },
                )
            },
    ) {
        val prepared = geometry ?: return@Canvas
        if (size.width <= 0f) return@Canvas
        val fullDetail = !moving.value && fullReady.value == orientation.value
        val mesh = if (fullDetail) prepared.full else prepared.drag.apply { update(orientation.value) }
        withTransform({ scale(size.width / 400f, size.width / 400f, Offset.Zero) }) {
            drawPlanetGeography(mesh, brushes)
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        if (geometry == null || size.width <= 0f) return@Canvas
        withTransform({ scale(size.width / 400f, size.width / 400f, Offset.Zero) }) {
            drawPlanetEdge(brushes)
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val prepared = geometry ?: return@Canvas
        if (size.width <= 0f) return@Canvas
        routeGeometry.update(orientation.value, if (confirmation) arcReveal.floatValue else 0f)
        withTransform({ scale(size.width / 400f, size.width / 400f, Offset.Zero) }) {
            drawGlobeRoute(routeGeometry, prepared.full.sphere, brushes, confirmation, 1.dp.toPx() * 400f / size.width)
        }
    }
    val persistentServer = callout == GlobeCallout.SERVER
    val calloutLocation = when (callout) {
        GlobeCallout.DEVICE -> deviceLocation
        GlobeCallout.SERVER -> exitLocation
        GlobeCallout.NONE -> null
    }
    val calloutPoint = when (callout) {
        GlobeCallout.DEVICE -> route.device
        GlobeCallout.SERVER -> route.exit
        GlobeCallout.NONE -> null
    }
    val calloutVisible by remember(calloutPoint, persistentServer) {
        derivedStateOf {
            calloutPoint != null && (persistentServer || GlobeProjection(orientation.value).rotate(calloutPoint)[2] > .02f)
        }
    }
    if (calloutVisible && calloutLocation != null && calloutPoint != null) {
        val point = remember(calloutPoint) { FloatArray(3) }
        val anchor = remember(calloutPoint) { FloatArray(2) }
        var calloutHeight by remember(calloutPoint) { mutableIntStateOf(0) }
        val calloutGap = with(LocalDensity.current) { 8.dp.toPx() }
        val calloutBelow by remember(routeGeometry, persistentServer, canvasWidth, calloutGap) {
            derivedStateOf {
                if (!persistentServer) false else {
                    routeGeometry.update(orientation.value, arcReveal.floatValue)
                    val scale = canvasWidth / 400f
                    globeCalloutBelow(routeGeometry.device?.times(scale), routeGeometry.exit?.times(scale),
                        calloutHeight.toFloat(), canvasWidth * 410f / 400f, calloutGap)
                }
            }
        }
        VpnGlobeCallout(
            calloutLocation,
            Modifier.widthIn(max = 200.dp).onSizeChanged { calloutHeight = it.height }.graphicsLayer {
                GlobeProjection(orientation.value).rotate(calloutPoint, point)
                val scale = canvasWidth / 400f
                placeGlobeCallout(point, persistentServer, anchor)
                if (persistentServer && point[2] > 0f) {
                    routeGeometry.update(orientation.value, arcReveal.floatValue)
                    routeGeometry.exit?.let { projected ->
                        anchor[0] += (projected.x - (200f + 145f * point[0])) / 145f
                        anchor[1] -= (projected.y - (193f - 145f * point[1])) / 145f
                    }
                }
                alpha = if (persistentServer || point[2] > .02f) 1f else 0f
                translationX = ((200f + 145f * anchor[0]) * scale - size.width / 2f)
                    .coerceIn(0f, (canvasWidth - size.width).coerceAtLeast(0f))
                translationY = globeCalloutVerticalOffset((193f - 145f * anchor[1]) * scale, size.height,
                    canvasWidth * 410f / 400f, calloutGap, calloutBelow)
            }.then(if (persistentServer) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.vpn_close)) {
                if (currentConfirmation.value) calloutCue = calloutCue.dismissServer()
            } else Modifier),
            below = calloutBelow,
        )
    }
    }
}

private val GlobeOrientationSaver = listSaver<MutableState<GlobeOrientation>, Float>(
    save = { listOf(it.value.yaw, it.value.pitch) },
    restore = { mutableStateOf(GlobeOrientation(it[0], it[1])) },
)

// A new process must not inherit the old process's consumed connection ticket.
private val GlobeProcessId = UUID.randomUUID().toString()
private val GlobeCalloutCueSaver = Saver<MutableState<GlobeCalloutCue>, Any>(
    save = { it.value.save(GlobeProcessId) },
    restore = {
        mutableStateOf(GlobeCalloutCue.restore(it, GlobeProcessId))
    },
)

private val GlobeConnectionCueSaver = Saver<GlobeConnectionCue, Any>(
    save = { listOf(GlobeProcessId, it.consumedConnectionId) },
    restore = { GlobeConnectionCue.restore(it, GlobeProcessId) },
)

private class GlobeMotionJob {
    var job: Job? = null
    var generation: Int = 0
        private set
    var connectionActive: Boolean = false
        private set

    fun cancel(): Int {
        generation++
        job?.cancel()
        job = null
        connectionActive = false
        return generation
    }

    fun startConnection(): Int = cancel().also { connectionActive = true }

    fun finishConnection() { connectionActive = false }
}
private val GlobeRecenterSpec = tween<Float>(600, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))
private val GlobeConnectionSpec = tween<Float>(750, easing = CubicBezierEasing(.4f, 0f, .2f, 1f))

private class PreparedGlobe(legacyPaths: Boolean) {
    val full = PlanetGeometry(legacyPaths, fastUpdates = true, landOnly = true)
    val drag = PlanetGeometry(legacyPaths, fastUpdates = true, landOnly = true)
    val updateLock = Mutex()
}
