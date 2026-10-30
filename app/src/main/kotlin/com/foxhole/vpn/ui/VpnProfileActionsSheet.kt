package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary

@Composable
internal fun VpnProfileActionsSheet(
    profile: VpnProfileSummary,
    busy: Boolean,
    shareEnabled: Boolean,
    editable: Boolean,
    routerPaired: Boolean,
    onShare: () -> Unit,
    onSendToRouter: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val deleteColor = vpnDeleteColor()
    VpnSheetContent(R.string.vpn_profile_actions_title, R.drawable.lin_profiles, busy = busy) {
        Text(profile.name, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, enabled = shareEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                Icon(painterResource(R.drawable.lin_file), null, Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.vpn_profile_share), Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            val unavailable = stringResource(R.string.vpn_profile_refresh_unavailable)
            OutlinedButton(onClick = onRefresh, enabled = editable && profile.refreshable,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    .semantics { if (!profile.refreshable) stateDescription = unavailable },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                Icon(painterResource(R.drawable.lin_refresh), null, Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.vpn_refresh), Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        if (routerPaired) {
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = onSendToRouter,
                enabled = shareEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            ) {
                Icon(painterResource(R.drawable.lin_router), null, Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.vpn_profile_send_to_router), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.size(8.dp))
        val removeDescription = stringResource(R.string.vpn_remove_named, profile.name)
        OutlinedButton(onClick = onRemove, enabled = editable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics { contentDescription = removeDescription },
            border = BorderStroke(1.dp, deleteColor.copy(alpha = if (editable) 1f else .38f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = deleteColor.copy(alpha = .08f),
                contentColor = deleteColor)) {
            Icon(painterResource(R.drawable.lin_trash), null, Modifier.size(24.dp),
                tint = deleteColor.copy(alpha = if (editable) 1f else .38f))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.vpn_remove))
        }
    }
}
