package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.router.RouterControlState
import com.foxhole.vpn.router.routerStatusPresentation


@Composable
internal fun VpnRouterStatusCard(state: RouterControlState, onProfileClick: (() -> Unit)? = null, onLocalTrafficClick: (() -> Unit)? = null) {
    val status = routerStatusPresentation(state)
    val style = MaterialTheme.typography.bodyMedium
    val colors = LocalVpnColors.current
    val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() + 8.dp }.coerceAtLeast(40.dp)
    val profileChevronHeight = vpnFlagIconSize(style, 1, 1).height
    val connectionColor = when (status.connected) {
        true -> routerConnectColor()
        false -> MaterialTheme.colorScheme.error
        null -> colors.muted
    }
    val localTraffic = com.foxhole.vpn.router.routerCountryDirectControl(state.preferences, state.routerCountryCode, state.snapshot)
    val unknown = stringResource(R.string.router_review_status_unknown)
    Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.router_status_title), Modifier.fillMaxWidth().semantics { heading() },
                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp), tint = connectionColor)
                Text(when (status.connected) {
                    true -> stringResource(R.string.router_status_connected)
                    false -> stringResource(R.string.router_status_disconnected)
                    null -> unknown
                }, style = style, color = connectionColor, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight.coerceAtLeast(48.dp))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable(enabled = onProfileClick != null && state.controlsAvailable, onClick = { onProfileClick?.invoke() })
                .border(BorderStroke(1.dp, colors.border), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp), tint = colors.muted)
                Text(stringResource(R.string.router_status_profile), style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(status.profileName ?: if (status.connected == null) unknown else stringResource(R.string.router_status_no_profile),
                    Modifier.weight(1f), textAlign = TextAlign.End, style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (onProfileClick != null) Icon(painterResource(R.drawable.router_status_chevron), null,
                    Modifier.size(width = profileChevronHeight * (10f / 18f), height = profileChevronHeight), tint = colors.muted)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight.coerceAtLeast(48.dp))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable(enabled = onLocalTrafficClick != null && state.controlsAvailable && localTraffic.canChange, onClick = { onLocalTrafficClick?.invoke() }).border(BorderStroke(1.dp, colors.border), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(20.dp), tint = colors.muted)
                Text(stringResource(R.string.router_status_location), Modifier.weight(1f), style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (status.countryCode != null) {
                    VpnFlagIcon(countryCode = status.countryCode, style = style)
                    Text(status.countryCode, style = style)
                }
                else Text(if (status.localTraffic == false) stringResource(R.string.router_status_local_off) else unknown,
                    Modifier.weight(1f), textAlign = TextAlign.End, style = style, color = colors.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
