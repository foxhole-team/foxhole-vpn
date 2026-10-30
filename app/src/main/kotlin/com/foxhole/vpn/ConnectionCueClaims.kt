package com.foxhole.vpn

internal class ConnectionCueClaims {
    private var lastClaimed = 0L

    @Synchronized
    fun claim(connectionId: Long): Boolean {
        if (connectionId <= lastClaimed) return false
        lastClaimed = connectionId
        return true
    }
}
