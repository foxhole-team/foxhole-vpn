package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnAppRoute
import com.foxhole.vpn.VpnAppRouting
import com.foxhole.vpn.normalizeVpnWebMask
import com.foxhole.vpn.withWebRule

@Composable
internal fun VpnWebsiteRules(routing: VpnAppRouting, enabled: Boolean, listState: LazyListState,
    onChange: ((VpnAppRouting) -> VpnAppRouting) -> Unit) {
    var removing by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    fun remove(mask: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        removing = mask
    }
    Row(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.lin_world_www), null, Modifier.size(20.dp))
        Text(stringResource(R.string.vpn_web_rules), style = MaterialTheme.typography.titleSmall)
    }
    if (routing.webRules.isEmpty()) ScenarioEmptyState(stringResource(R.string.vpn_web_rules_empty))
    else Surface(Modifier.fillMaxWidth().heightIn(max = minOf((routing.webRules.size * 80).dp,
        LocalConfiguration.current.screenHeightDp.dp * .85f)), shape = RoundedCornerShape(14.dp),
        color = LocalVpnColors.current.panel, border = BorderStroke(1.dp, LocalVpnColors.current.border)) {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            LazyColumn(Modifier.fillMaxWidth().testTag("vpn-web-rules").nestedScroll(ScenarioListScrollConnection), state = listState) {
                itemsIndexed(routing.webRules, key = { _, rule -> rule.mask }) { index, rule ->
                    val removeLabel = stringResource(R.string.vpn_web_delete_title)
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .pointerInput(enabled, rule.mask) { if (enabled) detectTapGestures(onLongPress = { remove(rule.mask) }) }
                        .semantics { if (enabled) onLongClick(removeLabel) { remove(rule.mask); true } }
                        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.lin_world_www), null, Modifier.size(28.dp), tint = LocalVpnColors.current.muted)
                        Text(rule.mask, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        WebsiteRouteDropdown(rule.route, enabled) { route -> onChange { it.withWebRule(rule.mask, route) } }
                    }
                    if (index < routing.webRules.lastIndex) ScenarioDivider()
                }
            }
            VpnProfileScrollbar(listState, Modifier.matchParentSize(), "vpn-web-rules-scrollbar")
        }
    }
    removing?.let { mask ->
        VpnBottomSheet(onDismiss = { removing = null }, showCloseButton = false) {
            VpnSheetContent(R.string.vpn_web_delete_title, R.drawable.lin_trash) {
                Text(stringResource(R.string.vpn_web_delete_body, mask), Modifier.padding(bottom = 20.dp),
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { removing = null }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_cancel))
                    }
                    Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); removing = null; onChange { current -> current.copy(webRules = current.webRules.filterNot { it.mask == mask }) } },
                        enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_scenario_confirm_action))
                    }
                }
            }
        }
    }
}

@Composable
internal fun VpnWebsiteInputSheet(enabled: Boolean, onDismiss: () -> Unit, onAdd: (String, VpnAppRoute) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var route by rememberSaveable { mutableStateOf(VpnAppRoute.VPN) }
    var invalid by remember { mutableStateOf(false) }
    val normalizedAddress = remember(address) { normalizeVpnWebMask(address) }
    VpnBottomSheet(onDismiss = onDismiss, showCloseButton = false) {
        VpnSheetContent(R.string.vpn_add_website, R.drawable.lin_world_www, scrollableBody = true,
            footer = {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_cancel))
                    }
                    Button(enabled = enabled && address.isNotBlank(), onClick = {
                        val mask = normalizedAddress
                        if (mask == null) invalid = true else onAdd(mask, route)
                    }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vpn_web_add)) }
                }
            }) {
            OutlinedTextField(address, { address = it.take(2_048); invalid = false },
                enabled = enabled, singleLine = true, isError = invalid, modifier = Modifier.fillMaxWidth().testTag("vpn-web-address-input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                label = { Text(stringResource(R.string.vpn_web_address_mask)) })
            if (invalid) Text(stringResource(R.string.vpn_web_invalid),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.vpn_web_route), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                WebsiteRouteDropdown(route, enabled) { route = it }
            }
        }
    }
}

@Composable
private fun WebsiteRouteDropdown(route: VpnAppRoute, enabled: Boolean, onChange: (VpnAppRoute) -> Unit) {
    VpnChoiceDropdown(stringResource(scenarioRouteLabel(route)), enabled, Modifier.widthIn(max = 176.dp),
        leadingIcon = scenarioRouteIcon(route), alignRight = true) { dismiss ->
        VpnAppRoute.entries.forEach { value ->
            DropdownMenuItem(text = { Text(stringResource(scenarioRouteLabel(value)), maxLines = 1) },
                leadingIcon = { Icon(painterResource(scenarioRouteIcon(value)), null, Modifier.size(20.dp)) },
                onClick = { dismiss(); if (value != route) onChange(value) })
        }
    }
}
