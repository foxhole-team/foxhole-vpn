package com.foxhole.vpn

internal class ConnectionCommandFence {
    private var generation = 0L
    private var pending: Long? = null

    @Synchronized
    fun reserveStart(): Long = (++generation).also { pending = it }

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun reserveStartIfCurrent(expected: Long): Long? =
        if (generation == expected) reserveStart() else null

    @Synchronized
    fun isCurrent(ticket: Long): Boolean = ticket == generation

    @Synchronized
    fun hasPendingStart(): Boolean = pending != null

    @Synchronized
    fun isCurrentWithoutPendingStart(ticket: Long): Boolean = ticket == generation && pending == null

    @Synchronized
    fun dispatch(ticket: Long, action: () -> Unit): Boolean {
        if (ticket != generation || pending != ticket) return false
        action()
        pending = null
        return true
    }

    @Synchronized
    fun applyCurrent(ticket: Long, action: () -> Unit): Boolean {
        if (ticket != generation) return false
        action()
        return true
    }

    @Synchronized
    fun cancelPending(ticket: Long, action: () -> Unit): Boolean {
        if (ticket != generation || pending != ticket) return false
        stop(action = action)
        return true
    }

    @Synchronized
    fun abandon(ticket: Long): Boolean {
        if (ticket != generation) return false
        generation++
        pending = null
        return true
    }

    @Synchronized
    fun stop(preserving: Long? = null, action: () -> Unit) {
        if (preserving != null) {
            if (preserving != generation) return
        } else {
            generation++
            pending = null
        }
        action()
    }
}
