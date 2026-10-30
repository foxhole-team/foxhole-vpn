package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.appupdates.VpnAppUpdateError
import com.foxhole.vpn.appupdates.VpnAppUpdatePhase
import com.foxhole.vpn.appupdates.VpnAppUpdateState

internal fun appUpdateErrorResource(error: VpnAppUpdateError?): Int = when (error) {
    VpnAppUpdateError.NETWORK -> R.string.vpn_app_update_error_network
    VpnAppUpdateError.RATE_LIMITED -> R.string.vpn_app_update_error_rate
    VpnAppUpdateError.METADATA, VpnAppUpdateError.VERIFICATION -> R.string.vpn_app_update_error_verify
    VpnAppUpdateError.STORAGE -> R.string.vpn_app_update_error_storage
    else -> R.string.vpn_app_update_error_install
}

@Composable
internal fun VpnAppUpdateCard(state: VpnAppUpdateState, onAction: () -> Unit) {
    val progress = if (state.phase == VpnAppUpdatePhase.DOWNLOADING && state.totalBytes > 0) {
        (state.downloadedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f)
    } else null
    VpnDataUpdateCard(
        title = stringResource(R.string.vpn_app_update_title),
        subtitle = appUpdateDetail(state), progress = progress, busy = state.isBusy,
        enabled = !state.isBusy, onClick = onAction, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun VpnAppUpdateSheet(state: VpnAppUpdateState, onAction: () -> Unit, onCheck: () -> Unit) {
    VpnSheetContent(R.string.vpn_app_update_title, R.drawable.lin_download, busy = state.isBusy) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appUpdateDetail(state), style = MaterialTheme.typography.bodyMedium)
            if (state.phase in setOf(VpnAppUpdatePhase.AVAILABLE, VpnAppUpdatePhase.DOWNLOADING,
                    VpnAppUpdatePhase.VERIFYING, VpnAppUpdatePhase.READY, VpnAppUpdatePhase.ERROR)) {
                VpnAppUpdateCard(state, onAction)
            }
            Button(onClick = onCheck, enabled = !state.isBusy && state.phase != VpnAppUpdatePhase.DISABLED,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.vpn_app_update_check))
            }
        }
    }
}

@Composable
private fun appUpdateDetail(state: VpnAppUpdateState): String = when (state.phase) {
    VpnAppUpdatePhase.CHECKING -> stringResource(R.string.vpn_app_update_checking)
    VpnAppUpdatePhase.NO_RELEASE -> stringResource(R.string.vpn_app_update_no_release)
    VpnAppUpdatePhase.UP_TO_DATE -> stringResource(R.string.vpn_app_update_current)
    VpnAppUpdatePhase.AVAILABLE -> stringResource(R.string.vpn_app_update_available, state.release?.manifest?.versionName.orEmpty())
    VpnAppUpdatePhase.DOWNLOADING -> if (state.totalBytes > 0) stringResource(R.string.vpn_app_update_percent,
        (state.downloadedBytes.toDouble() / state.totalBytes * 100).toInt().coerceIn(0, 100)) else stringResource(R.string.vpn_app_update_downloading)
    VpnAppUpdatePhase.VERIFYING -> stringResource(R.string.vpn_app_update_verifying)
    VpnAppUpdatePhase.READY -> stringResource(R.string.vpn_app_update_ready, state.release?.manifest?.versionName.orEmpty())
    VpnAppUpdatePhase.ERROR -> stringResource(appUpdateErrorResource(state.error))
    VpnAppUpdatePhase.DISABLED -> stringResource(R.string.vpn_app_update_external_source)
    VpnAppUpdatePhase.IDLE -> stringResource(R.string.vpn_app_update_intro)
}
