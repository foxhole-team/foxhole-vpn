package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VpnProtocolBadge(protocol: String, modifier: Modifier = Modifier) {
    val colors = LocalVpnColors.current
    Surface(
        modifier,
        shape = RoundedCornerShape(6.dp),
        color = colors.panel,
        contentColor = colors.muted,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Text(
            protocolDisplayName(protocol),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            maxLines = 1,
        )
    }
}

internal fun protocolDisplayName(protocol: String): String = when (protocol.uppercase(java.util.Locale.ROOT)) {
    "VMESS" -> "VMess"
    "HYSTERIA2" -> "Hysteria2"
    "SHADOWSOCKS" -> "Shadowsocks"
    "WIREGUARD" -> "WireGuard"
    "TROJAN" -> "Trojan"
    "NAIVE" -> "Naive"
    "ANYTLS" -> "AnyTLS"
    "OUTLINE" -> "Outline"
    else -> protocol.uppercase(java.util.Locale.ROOT)
}
