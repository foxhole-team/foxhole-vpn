package com.foxhole.vpn.refresh

internal data class SubscriptionRefreshRun(
    val checked: Int = 0,
    val updated: Int = 0,
    val failed: Int = 0,
    val deferred: Int = 0,
    val stale: Int = 0,
    val retry: Boolean = false,
)
