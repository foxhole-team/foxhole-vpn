package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnDataUpdateCard(
    title: String,
    subtitle: String,
    progress: Float?,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = !busy,
) {
    val colors = LocalVpnColors.current
    val downloadColor = if (colors.monochrome) colors.foreground else DownloadBlue
    val fraction = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Surface(
        modifier = modifier
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (busy) {
                    progressBarRangeInfo = fraction?.let { ProgressBarRangeInfo(it, 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = colors.panel,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.border.copy(alpha = .6f)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(downloadColor.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.lin_download),
                    contentDescription = null,
                    modifier = Modifier.size(23.dp),
                    tint = downloadColor,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (busy) {
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(24.dp),
                        color = downloadColor,
                        strokeWidth = 2.dp,
                        trackColor = colors.border.copy(alpha = .5f),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = downloadColor, strokeWidth = 2.dp)
                }
            } else {
                Icon(
                    painterResource(R.drawable.lin_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.muted,
                )
            }
        }
    }
}

private val DownloadBlue = Color(0xFF3B93D0)
