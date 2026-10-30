package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLicenseState
import com.foxhole.vpn.VpnUpdateNoticeState

@Composable
internal fun VpnAppStartup(
    license: VpnLicenseState,
    notice: VpnUpdateNoticeState,
    onAcceptLicense: (Boolean) -> Unit,
    onAcknowledgeNotice: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    VpnLicenseGate(license, onAcceptLicense, onClose) {
        content()
        if (notice.loaded && !notice.acknowledged) {
            key(notice.retryGeneration) {
                VpnUpdateNoticeSheet(notice.saving, notice.storageError, onDismiss = onAcknowledgeNotice)
            }
        }
    }
}

@Composable
private fun VpnUpdateNoticeSheet(
    saving: Boolean,
    storageError: Boolean,
    onDismiss: () -> Unit,
) {
    VpnBottomSheet(onDismiss = { if (!saving) onDismiss() }, gesturesEnabled = !saving,
        confineContentGestures = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.lin_refresh), null, Modifier.size(24.dp))
                Text(stringResource(R.string.vpn_release_notes_title), style = MaterialTheme.typography.titleLarge)
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, LocalVpnColors.current.border),
                    color = LocalVpnColors.current.panel) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        VpnReleaseNoteRow(R.drawable.lin_file, R.string.vpn_release_notes_formats)
                        VpnReleaseNoteRow(R.drawable.lin_profiles, R.string.vpn_release_notes_imports)
                        VpnReleaseNoteRow(R.drawable.lin_router, R.string.vpn_release_notes_router)
                    }
                }
                if (storageError) {
                    Text(stringResource(R.string.vpn_release_notes_storage_error),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun VpnReleaseNoteRow(icon: Int, text: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(text), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
