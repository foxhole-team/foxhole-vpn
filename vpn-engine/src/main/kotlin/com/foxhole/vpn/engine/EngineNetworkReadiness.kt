package com.foxhole.vpn.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T : Any> awaitAccessibleVpnNetwork(
    candidate: () -> T?,
    accessible: (T) -> Boolean,
): T? = withTimeoutOrNull(5_000) {
    var ready: T? = null
    while (ready == null) {
        val network = candidate()
        if (network != null && accessible(network)) ready = network else delay(50)
    }
    ready
}
