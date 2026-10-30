package com.foxhole.vpn.ui

import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.ui.geometry.Offset
import com.foxhole.vpn.VpnLocation
import kotlin.math.abs
import kotlin.math.hypot

internal fun globeFlingMotion(
    velocityX: Float,
    velocityY: Float,
    widthPixels: Int,
): GlobeFlingMotion? {
    if (widthPixels <= 0 || !velocityX.isFinite() || !velocityY.isFinite()) return null
    val horizontal = globeAngularVelocity(velocityX, widthPixels)
    val vertical = globeAngularVelocity(velocityY, widthPixels)
    val speed = hypot(horizontal, vertical)
    if (!speed.isFinite() || speed <= GlobeInertia.stopSpeed) return null
    val cappedSpeed = speed.coerceAtMost(GlobeInertia.maximumSpeed)
    val scale = cappedSpeed / speed
    return GlobeFlingMotion(horizontal * scale, vertical * scale, cappedSpeed)
}

internal fun globeMotionAllowed(durationScale: Float, resumed: Boolean): Boolean =
    resumed && durationScale.isFinite() && durationScale > 0f

internal fun globeMarkerHit(device: Offset?, server: Offset?, touch: Offset, radius: Float, nearTie: Float, shown: GlobeCallout): GlobeCallout {
    if (!radius.isFinite() || radius <= 0f || !nearTie.isFinite() || nearTie < 0f) return GlobeCallout.NONE
    val deviceDistance = device?.let { (it - touch).getDistance() }?.takeIf { it.isFinite() && it <= radius }
    val serverDistance = server?.let { (it - touch).getDistance() }?.takeIf { it.isFinite() && it <= radius }
    return when {
        deviceDistance != null && serverDistance != null -> {
            if (abs(deviceDistance - serverDistance) <= nearTie) {
                if (shown == GlobeCallout.SERVER) GlobeCallout.DEVICE else GlobeCallout.SERVER
            } else if (deviceDistance < serverDistance) GlobeCallout.DEVICE else GlobeCallout.SERVER
        }
        deviceDistance != null -> GlobeCallout.DEVICE
        serverDistance != null -> GlobeCallout.SERVER
        else -> GlobeCallout.NONE
    }
}

internal class GlobeFlingMotion(
    private val horizontal: Float,
    private val vertical: Float,
    val initialSpeed: Float,
) {
    val decay: FloatDecayAnimationSpec = BaseDecay

    fun orientationAt(start: GlobeOrientation, travelledDegrees: Float): GlobeOrientation {
        val distance = travelledDegrees.takeIf { it.isFinite() }?.coerceIn(0f, GlobeInertia.maximumTravel) ?: 0f
        val result = start.turn(horizontal / initialSpeed * distance, vertical / initialSpeed * distance)
        val yaw = ((result.yaw - start.yaw) % 360f + 540f) % 360f - 180f
        val travel = hypot(yaw, result.pitch - start.pitch)
        return if (travel > GlobeInertia.maximumTravel) start.toward(result, GlobeInertia.maximumTravel / travel) else result
    }
}

internal class GlobeDragHaptics {
    private var active = false
    private var distance = 0f
    private var lastTickMillis: Long? = null
    private var lastEventMillis: Long? = null

    fun beginDrag() {
        active = true
        distance = 0f
    }

    fun endDrag() {
        active = false
        distance = 0f
    }

    fun onDrag(previous: GlobeOrientation, current: GlobeOrientation, eventMillis: Long): Boolean {
        if (!active || eventMillis < 0 || lastEventMillis?.let { eventMillis < it } == true) return false
        if (!previous.yaw.isFinite() || !previous.pitch.isFinite() || !current.yaw.isFinite() || !current.pitch.isFinite()) return false
        lastEventMillis = eventMillis
        val yaw = ((current.yaw - previous.yaw) % 360f + 540f) % 360f - 180f
        val step = hypot(yaw, current.pitch - previous.pitch)
        if (step == 0f || !step.isFinite()) return false
        distance = (distance + step).coerceAtMost(HapticStep)
        if (distance < HapticStep || lastTickMillis?.let { eventMillis - it < HapticIntervalMillis } == true) return false
        lastTickMillis = eventMillis
        distance = 0f
        return true
    }
}

internal fun validatedGlobeExit(connected: Boolean, location: VpnLocation?): Boolean =
    connected && !location?.ip.isNullOrBlank() && GlobeCoordinates.from(location?.latitude, location?.longitude) != null

internal class GlobeConnectionCue(var consumedConnectionId: Long? = null) {
    companion object {
        fun restore(saved: Any?, processId: String): GlobeConnectionCue {
            val fields = saved as? List<*>
            val consumed = if (fields?.getOrNull(0) == processId) fields.getOrNull(1) as? Long else null
            return GlobeConnectionCue(consumed)
        }
    }

    fun beginIfNew(connectionId: Long?, confirmed: Boolean): Boolean {
        if (connectionId == null) {
            consumedConnectionId = null
            return false
        }
        if (!confirmed || consumedConnectionId == connectionId) return false
        consumedConnectionId = connectionId
        return true
    }
}

private val BaseDecay = object : FloatDecayAnimationSpec {
    override val absVelocityThreshold = GlobeInertia.stopSpeed
    override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
        initialValue + GlobeInertia.distance(initialVelocity, playTimeNanos)
    override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long = GlobeInertia.durationNanos(initialVelocity)
    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, initialVelocity: Float): Float =
        GlobeInertia.velocity(initialVelocity, playTimeNanos)
    override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float = initialValue + GlobeInertia.target(initialVelocity)
}
private const val HapticStep = 11f
private const val HapticIntervalMillis = 80L
