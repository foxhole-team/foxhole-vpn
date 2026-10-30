package com.foxhole.vpn.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

internal object GlobeInertia {
    const val stopSpeed = 8f
    const val maximumSpeed = 160f
    const val maximumTravel = 52f
    private const val deceleration = 3.024

    fun durationNanos(velocity: Float): Long = if (!velocity.isFinite() || abs(velocity) <= stopSpeed) 0L
        else (ln(abs(velocity) / stopSpeed) / deceleration * 1_000_000_000.0).toLong()

    fun distance(velocity: Float, elapsedNanos: Long): Float {
        if (!velocity.isFinite() || elapsedNanos <= 0L) return 0f
        val seconds = elapsedNanos.coerceAtMost(durationNanos(velocity)) / 1_000_000_000.0
        return (velocity / deceleration * (1.0 - exp(-deceleration * seconds))).toFloat()
    }

    fun velocity(velocity: Float, elapsedNanos: Long): Float {
        if (!velocity.isFinite() || abs(velocity) <= stopSpeed || elapsedNanos >= durationNanos(velocity)) return 0f
        return (velocity * exp(-deceleration * elapsedNanos.coerceAtLeast(0L) / 1_000_000_000.0)).toFloat()
    }

    fun target(velocity: Float): Float = if (!velocity.isFinite() || abs(velocity) <= stopSpeed) 0f
        else ((velocity - sign(velocity) * stopSpeed) / deceleration).toFloat()
}
