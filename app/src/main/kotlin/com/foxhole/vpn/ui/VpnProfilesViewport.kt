package com.foxhole.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

@Composable
internal fun vpnProfileRowHeight(): Dp = with(LocalDensity.current) {
    maxOf(64.dp, MaterialTheme.typography.bodyLarge.lineHeight.toDp() + MaterialTheme.typography.bodySmall.lineHeight.toDp() + 21.dp)
}

internal fun vpnProfilesViewportHeight(profileCount: Int, expanded: Boolean, rowHeight: Dp, density: Density = Density(1f)): Dp {
    val rows = if (expanded && profileCount > 0) 6 else profileCount.coerceIn(0, 6)
    return with(density) { (rowHeight.roundToPx() * rows + 8.dp.roundToPx() * (rows - 1).coerceAtLeast(0)).toDp() }
}
