package com.foxhole.vpn.ui

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable

@Composable
internal fun vpnNeutralButtonColors(): ButtonColors {
    val colors = LocalVpnColors.current
    return if (colors.monochrome) ButtonDefaults.buttonColors(
        containerColor = colors.panel, contentColor = colors.foreground,
        disabledContainerColor = colors.panel, disabledContentColor = colors.muted,
    ) else ButtonDefaults.buttonColors()
}

@Composable
internal fun vpnNeutralOutlinedButtonColors(): ButtonColors {
    val colors = LocalVpnColors.current
    return if (colors.monochrome) ButtonDefaults.outlinedButtonColors(
        contentColor = colors.foreground, disabledContentColor = colors.muted,
    ) else ButtonDefaults.outlinedButtonColors()
}

@Composable
internal fun vpnNeutralTextButtonColors(): ButtonColors {
    val colors = LocalVpnColors.current
    return if (colors.monochrome) ButtonDefaults.textButtonColors(
        contentColor = colors.foreground, disabledContentColor = colors.muted,
    ) else ButtonDefaults.textButtonColors()
}

@Composable
internal fun vpnPrimaryButtonColors(disconnect: Boolean): ButtonColors {
    val colors = LocalVpnColors.current
    return ButtonDefaults.buttonColors(
        containerColor = if (colors.monochrome) colors.foreground else androidx.compose.ui.graphics.Color(
            if (disconnect) 0xFFB3261E else 0xFF1E6C39),
        contentColor = if (colors.monochrome) colors.background else androidx.compose.ui.graphics.Color.White,
    )
}

@Composable
internal fun vpnBrightNightButtonColors(): ButtonColors {
    val colors = LocalVpnColors.current
    return if (colors.monochrome) ButtonDefaults.buttonColors(
        containerColor = colors.foreground, contentColor = colors.background,
        disabledContainerColor = colors.panel, disabledContentColor = colors.muted,
    ) else vpnNeutralButtonColors()
}
