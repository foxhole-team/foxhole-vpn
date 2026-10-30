package com.foxhole.vpn.ui

internal fun requestRouterAccessForEntry(
    isCurrent: () -> Boolean,
    requestAccess: ((() -> Unit) -> Unit)?,
    onUnavailable: () -> Unit,
    action: () -> Unit,
) {
    if (!isCurrent()) return
    if (requestAccess == null) {
        onUnavailable()
        return
    }
    requestAccess { if (isCurrent()) action() }
}
