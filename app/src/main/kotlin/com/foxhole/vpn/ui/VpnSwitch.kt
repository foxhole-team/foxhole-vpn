package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun VpnSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalVpnColors.current
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.scale(.8f).height(28.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = colors.muted,
                uncheckedTrackColor = colors.panel,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}
