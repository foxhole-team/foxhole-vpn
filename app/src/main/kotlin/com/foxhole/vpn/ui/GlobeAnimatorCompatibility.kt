package com.foxhole.vpn.ui

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

@ChecksSdkIntAtLeast(api = 26, lambda = 2)
internal inline fun globeAnimatorDurationScale(
    sdkInt: Int = Build.VERSION.SDK_INT,
    legacyScale: () -> Float,
    animatorsEnabled: () -> Boolean,
): Float = if (sdkInt >= 26) {
    if (animatorsEnabled()) 1f else 0f
} else legacyScale()
