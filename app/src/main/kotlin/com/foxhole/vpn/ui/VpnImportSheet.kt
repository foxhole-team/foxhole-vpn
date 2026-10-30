package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnImportSheet(
    enabled: Boolean,
    onPaste: () -> Unit,
    onFile: () -> Unit,
    onManual: () -> Unit,
    onQr: () -> Unit,
) {
    VpnSheetContent(R.string.vpn_no_profile, R.drawable.lin_plus, Modifier.verticalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, action) in listOf(
                R.string.vpn_import_from_clipboard to onPaste,
                R.string.vpn_import_file to onFile,
                R.string.vpn_import_qr to onQr,
                R.string.vpn_import_manual to onManual,
            )) {
                SettingsRow(label, enabled = enabled, onClick = action)
            }
        }
    }
}
