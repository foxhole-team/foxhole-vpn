package com.foxhole.vpn.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.ui.LocalVpnColors

@Composable
internal fun QrScanContent(
    state: QrScanState,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    preview: @Composable (Long) -> Unit,
) {
    val colors = LocalVpnColors.current
    Column(
        Modifier.fillMaxSize().background(colors.background).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.vpn_qr_title), style = MaterialTheme.typography.titleLarge,
            color = colors.foreground, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.vpn_qr_hint), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val token = state.token
            if (token != null) {
                val description = stringResource(R.string.vpn_qr_preview)
                Box(Modifier.size(minOf(maxWidth, maxHeight)).clip(MaterialTheme.shapes.large)
                    .background(colors.panel).semantics { contentDescription = description }) {
                    preview(token)
                    QrReticle(colors.connected)
                }
            } else {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!state.permissionGranted) {
                        Text(stringResource(R.string.vpn_qr_camera_permission_title), style = MaterialTheme.typography.titleMedium, color = colors.foreground)
                        Text(stringResource(R.string.vpn_qr_camera_permission_body), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                        Button(onClick = onPermission, enabled = !state.permissionPending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.vpn_qr_allow_camera))
                        }
                        OutlinedButton(onClick = onSettings, enabled = !state.permissionPending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.vpn_qr_open_settings))
                        }
                    } else if (state.cameraUnavailable) {
                        Text(stringResource(R.string.vpn_qr_camera_unavailable), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.vpn_qr_retry))
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.vpn_qr_back))
        }
    }
}

@Composable
private fun QrReticle(color: Color) {
    Canvas(Modifier.fillMaxSize().padding(24.dp)) {
        val arm = 26.dp.toPx()
        val stroke = 3.dp.toPx()
        for ((x, y) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
            val horizontal = if (x == 0f) arm else -arm
            val vertical = if (y == 0f) arm else -arm
            drawLine(color, Offset(x, y), Offset(x + horizontal, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + vertical), stroke, StrokeCap.Round)
        }
    }
}
