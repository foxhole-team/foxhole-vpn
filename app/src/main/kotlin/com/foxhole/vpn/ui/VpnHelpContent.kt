package com.foxhole.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnHelpContent(state: VpnHelpState = rememberVpnHelpState()) {
    val scroll = state.scroll
    val topics = remember { vpnHelpTopics() }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        topics.forEach { topic -> key(topic.id) { VpnHelpTopic(topic, state) } }
    }
}

@Composable
private fun VpnHelpTopic(topic: VpnHelpTopic, state: VpnHelpState) {
    val colors = LocalVpnColors.current
    val expanded = topic.id in state.expanded.value
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.panel,
        contentColor = colors.foreground, border = BorderStroke(1.dp, colors.border.copy(alpha = if (expanded) .8f else .4f))) {
        Column {
            VpnHelpTopicHeader(topic, expanded) { state.expanded.value = if (expanded) state.expanded.value - topic.id else state.expanded.value + topic.id }
            AnimatedVisibility(expanded,
                enter = expandVertically(VpnSheetMotion.defaultSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(VpnSheetMotion.fastEffectsSpec()),
                exit = shrinkVertically(VpnSheetMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) + fadeOut(VpnSheetMotion.fastEffectsSpec())) {
                VpnHelpBlocks(topic.blocks, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), scope = topic.id)
            }
        }
    }
}

@Composable
private fun VpnHelpTopicHeader(topic: VpnHelpTopic, expanded: Boolean, onToggle: (() -> Unit)?) {
    val colors = LocalVpnColors.current
    val state = stringResource(if (expanded) R.string.help_expanded else R.string.help_collapsed)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
        .semantics(mergeDescendants = true) { heading(); stateDescription = state }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(topic.icon), null, Modifier.size(22.dp), tint = colors.muted)
        Text(stringResource(topic.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif), maxLines = 2)
        Icon(painterResource(R.drawable.lin_chevron_down), null,
            Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = colors.muted)
    }
}
