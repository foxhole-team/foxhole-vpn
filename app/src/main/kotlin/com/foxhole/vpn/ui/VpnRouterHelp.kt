package com.foxhole.vpn.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnRouterHelpButton(@StringRes titleRes: Int, @StringRes helpRes: Int) {
    var expanded by remember(titleRes, helpRes) { mutableStateOf(false) }
    Column {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                painterResource(R.drawable.lin_help),
                stringResource(R.string.router_help_open, stringResource(titleRes)),
                Modifier.size(22.dp),
                tint = LocalVpnColors.current.muted,
            )
        }
        if (expanded) VpnBottomSheet(onDismiss = { expanded = false }) {
            VpnSheetContent(titleRes, R.drawable.lin_help, Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(helpRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
