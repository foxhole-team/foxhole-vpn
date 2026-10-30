package com.foxhole.vpn.ui

internal fun globeDragOrientation(
    orientation: GlobeOrientation,
    deltaX: Float,
    deltaY: Float,
    widthPixels: Int,
): GlobeOrientation {
    if (widthPixels <= 0 || !orientation.yaw.isFinite() || !orientation.pitch.isFinite() ||
        !deltaX.isFinite() || !deltaY.isFinite()
    ) return orientation
    return orientation.turn(globeAngularVelocity(deltaX, widthPixels), globeAngularVelocity(deltaY, widthPixels))
}
