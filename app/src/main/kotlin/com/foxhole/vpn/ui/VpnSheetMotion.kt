package com.foxhole.vpn.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

// Material3 1.4 keeps MotionScheme internal; use standard fast tokens.
internal object VpnSheetMotion {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = .9f, stiffness = 1400f)
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

    fun pageTransition(animateSize: Boolean = true, forward: Boolean = true): ContentTransform = ContentTransform(
        targetContentEnter = slideInVertically(defaultSpatialSpec()) { if (forward) it / 8 else -it / 8 } + fadeIn(fastEffectsSpec()),
        initialContentExit = slideOutVertically(defaultSpatialSpec()) { if (forward) -it / 8 else it / 8 } + fadeOut(fastEffectsSpec()),
        sizeTransform = if (animateSize) SizeTransform { _, _ -> defaultSpatialSpec() } else null,
    )
}
