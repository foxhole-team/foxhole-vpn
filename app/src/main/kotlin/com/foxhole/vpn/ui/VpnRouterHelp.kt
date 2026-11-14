package com.foxhole.vpn.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnRouterHelpButton(@StringRes titleRes: Int, @StringRes helpRes: Int,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium, actionStyle: Boolean = false,
    helpContent: (@Composable () -> Unit)? = null,
    canInstallRouter: Boolean = false, canInspectRouter: Boolean = true, @StringRes actionLabelRes: Int? = null) {
    var expanded by rememberSaveable(titleRes, helpRes) { mutableStateOf(false) }
    val description = stringResource(R.string.router_help_open, stringResource(titleRes))
    val iconSize = if (actionStyle) 24.dp else maxOf(20.dp, vpnFlagIconSize(textStyle, imageWidth = 1, imageHeight = 1).height)
    Box {
        if (actionLabelRes != null) TextButton(colors = vpnNeutralTextButtonColors(), onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.lin_help), null, Modifier.size(22.dp), tint = LocalVpnColors.current.muted)
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Text(stringResource(actionLabelRes), maxLines = 2)
        } else Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = description) { expanded = !expanded }
            .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.lin_help),
                null,
                Modifier.size(iconSize),
                tint = if (actionStyle) LocalVpnColors.current.foreground else LocalVpnColors.current.muted,
            )
        }
        if (expanded) VpnBottomSheet(onDismiss = { expanded = false }) {
            VpnSheetContent(if (helpRes == R.string.router_help_setup_pair) R.string.help_router_title else titleRes,
                R.drawable.lin_help, Modifier.verticalScroll(rememberScrollState()), titleMaxLines = 2) {
                val blocks = vpnRouterContextBlocks(helpRes, canInstallRouter, canInspectRouter)
                if (helpContent != null) helpContent()
                else if (blocks != null) VpnHelpBlocks(blocks, scope = "router-$helpRes")
                else Text(stringResource(helpRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun VpnHelpLabel(label: String, @StringRes titleRes: Int, @StringRes helpRes: Int,
    modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current, maxLines: Int = Int.MAX_VALUE) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.weight(1f), style = style, color = color,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        VpnRouterHelpButton(titleRes, helpRes, textStyle = style)
    }
}
