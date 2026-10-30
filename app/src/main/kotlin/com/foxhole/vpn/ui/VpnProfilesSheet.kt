package com.foxhole.vpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnUiState

internal class VpnProfilesUiState {
    var expandedProfile by mutableStateOf<String?>(null)
    val listState = LazyListState()
}

internal const val VpnProfilesListTag = "vpn-profiles-list"
internal fun vpnProtocolListTag(profileId: String): String = "vpn-protocols-$profileId"

@Composable
internal fun VpnProfilesSheet(
    state: VpnUiState,
    busy: Boolean,
    ui: VpnProfilesUiState,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onActions: (VpnProfileSummary) -> Unit,
    onSelectProtocol: (String, String) -> Unit,
    interactionAllowed: () -> Boolean = { true },
) {
    val interactionEnabled = interactionAllowed()
    val editable = interactionEnabled && !busy && !state.canDisconnect && state.isReady
    val rowHeight = vpnProfileRowHeight()
    val expanded = state.profiles.any { it.id == ui.expandedProfile }
    val viewportHeight by animateDpAsState(
        vpnProfilesViewportHeight(state.profiles.size, expanded, rowHeight, LocalDensity.current),
        VpnSheetMotion.defaultSpatialSpec(), label = "vpn-profiles-height",
    )
    VpnSheetContent(R.string.vpn_profiles, R.drawable.lin_profiles, busy = busy, actions = {
        IconButton(onClick = onAdd, enabled = interactionEnabled && !busy && state.isReady, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.lin_plus), stringResource(R.string.vpn_no_profile), Modifier.size(24.dp))
        }
    }) {
        if (state.profiles.isEmpty()) Text(stringResource(R.string.vpn_profile_empty), Modifier.padding(vertical = 24.dp))
        Box(Modifier.weight(1f, fill = false).heightIn(max = viewportHeight).fillMaxWidth().clipToBounds()) {
            LazyColumn(Modifier.fillMaxWidth().testTag(VpnProfilesListTag).nestedScroll(ProfileListScrollConnection), contentPadding = PaddingValues(end = 12.dp),
                state = ui.listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.profiles, key = VpnProfileSummary::id) { profile ->
                    val selected = profile.id == state.selectedProfileId
                    val expanded = ui.expandedProfile == profile.id
                    val protocolEnabled = interactionEnabled && !busy && state.isReady &&
                        ((!state.canDisconnect && !state.isConnected) || (selected && state.hasOwnedTunnel &&
                            state.phase in setOf(VpnPhase.CONNECTED, VpnPhase.VALIDATING, VpnPhase.ERROR)))
                    VpnProfileCard(profile, selected, expanded, editable, protocolEnabled, rowHeight,
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = VpnSheetMotion.defaultSpatialSpec()),
                        onSelect = { onSelect(profile.id) },
                        onToggleDetails = {
                            if (interactionAllowed()) {
                                ui.expandedProfile = if (expanded) null else profile.id
                            }
                        },
                        onActions = { if (interactionAllowed() && !busy && state.isReady) onActions(profile) },
                        onSelectProtocol = { onSelectProtocol(profile.id, it) })
                }
            }
            VpnProfileScrollbar(ui.listState, Modifier.matchParentSize())
        }
    }
}

private object ProfileListScrollConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VpnProfileCard(
    profile: VpnProfileSummary,
    selected: Boolean,
    expanded: Boolean,
    editable: Boolean,
    protocolEnabled: Boolean,
    headerHeight: Dp,
    modifier: Modifier,
    onSelect: () -> Unit,
    onToggleDetails: () -> Unit,
    onActions: () -> Unit,
    onSelectProtocol: (String) -> Unit,
) {
    val colors = LocalVpnColors.current
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, VpnSheetMotion.defaultSpatialSpec(), label = "vpn-profile-chevron")
    Surface(modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), color = colors.panel, contentColor = colors.foreground,
        border = BorderStroke(1.dp, if (selected) colors.connected.copy(alpha = .6f) else colors.border.copy(alpha = .4f))) {
        Column {
            val detailsLabel = stringResource(if (expanded) R.string.vpn_profile_collapse_named else R.string.vpn_profile_expand_named, profile.name)
            val actionsLabel = stringResource(R.string.vpn_profile_actions_named, profile.name)
            Row(Modifier.fillMaxWidth().height(headerHeight).combinedClickable(
                role = Role.Button, onClick = onToggleDetails, onLongClick = onActions, onLongClickLabel = actionsLabel,
            )
                .semantics { contentDescription = detailsLabel }.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected, onClick = onSelect, enabled = editable, modifier = Modifier.size(48.dp))
                Row(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            VpnProtocolBadge(profile.protocol, Modifier.offset(y = (-5).dp))
                        }
                        if (selected) Text(stringResource(R.string.vpn_profile_selected), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
                Icon(painterResource(R.drawable.lin_chevron_down), null,
                    Modifier.size(24.dp).rotate(arrowRotation), tint = colors.muted)
            }
            AnimatedVisibility(expanded,
                enter = expandVertically(VpnSheetMotion.defaultSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(VpnSheetMotion.fastEffectsSpec()),
                exit = shrinkVertically(VpnSheetMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) + fadeOut(VpnSheetMotion.fastEffectsSpec()),
            ) {
                VpnProfileDetails(profile, protocolEnabled && expanded, headerHeight, onSelectProtocol)
            }
        }
    }
}

@Composable
private fun VpnProfileDetails(
    profile: VpnProfileSummary,
    enabled: Boolean,
    headerHeight: Dp,
    onSelectProtocol: (String) -> Unit,
) {
    val colors = LocalVpnColors.current
    if (profile.protocolOptions.size <= 1) {
        Text(stringResource(R.string.vpn_profile_protocol, profile.protocol),
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.muted)
        return
    }
    val viewportHeight = (vpnProfilesViewportHeight(1, true, headerHeight, LocalDensity.current) - headerHeight - 16.dp)
        .coerceAtLeast(52.dp)
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 16.dp)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = viewportHeight).testTag(vpnProtocolListTag(profile.id)),
            state = listState, contentPadding = PaddingValues(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profile.protocolOptions, key = VpnProtocolSummary::id) { option ->
                VpnProtocolOptionRow(option, option.id == profile.selectedProtocolOptionId, enabled) { onSelectProtocol(option.id) }
            }
        }
        VpnProfileScrollbar(listState, Modifier.matchParentSize(), "${vpnProtocolListTag(profile.id)}-scrollbar")
    }
}

@Composable
private fun VpnProtocolOptionRow(option: VpnProtocolSummary, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vpnProtocolOptionDisplayName(option.name, option.protocol), Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VpnProtocolBadge(option.protocol, Modifier.offset(y = (-5).dp))
        }
    }
}
