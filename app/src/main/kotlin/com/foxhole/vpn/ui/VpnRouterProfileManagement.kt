package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnRouterProfileManagementContent(name: String, protocol: String, profile: String?,
    loading: Boolean, provisioned: Boolean, enabled: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
            Text(protocol, style = MaterialTheme.typography.bodyMedium, color = LocalVpnColors.current.muted)
        }
        if (profile != null) VpnRouterClientQr(profile, name)
        else Text(stringResource(if (loading) R.string.router_control_profile_loading else R.string.router_control_qr_unavailable),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onImport, enabled = enabled && profile != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(painterResource(R.drawable.lin_download), null, Modifier.size(18.dp))
            Text(stringResource(R.string.router_control_import_phone), Modifier.padding(start = 8.dp))
        }
        if (!provisioned) Text(stringResource(R.string.router_control_client_template),
            style = MaterialTheme.typography.bodySmall, color = LocalVpnColors.current.muted)
    }
}

@Composable
internal fun VpnRouterProfileActionsFooter(enabled: Boolean, onShare: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight(), enabled = enabled) {
            Icon(painterResource(R.drawable.router_review_share), null, Modifier.size(18.dp))
            Text(stringResource(R.string.router_review_share), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight(), enabled = enabled,
            border = BorderStroke(1.dp, vpnDeleteColor()), colors = ButtonDefaults.outlinedButtonColors(contentColor = vpnDeleteColor())) {
            Icon(painterResource(R.drawable.lin_trash), null, Modifier.size(18.dp), tint = vpnDeleteColor())
            Text(stringResource(R.string.vpn_remove), Modifier.padding(start = 8.dp))
        }
    }
}
