package com.foxhole.vpn.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun vpnDeleteColor(): Color = if (LocalVpnColors.current.light) Color(0xFFB3261E) else Color(0xFFFFB4AB)
