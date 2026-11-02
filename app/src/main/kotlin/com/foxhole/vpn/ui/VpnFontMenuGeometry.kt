package com.foxhole.vpn.ui

internal data class VpnFontMenuGeometry(val widthPx: Int, val offsetPx: Int)

// Popup coordinates must use the visible window bounds, not an untransformed local width.
internal fun vpnFontMenuGeometry(
    nativeLeftPx: Int,
    nativeWidthPx: Int,
    visibleLeftPx: Int,
    visibleRightPx: Int,
    isRtl: Boolean,
): VpnFontMenuGeometry = VpnFontMenuGeometry(
    widthPx = (visibleRightPx - visibleLeftPx).coerceAtLeast(0),
    offsetPx = if (isRtl) nativeLeftPx + nativeWidthPx - visibleRightPx else visibleLeftPx - nativeLeftPx,
)
