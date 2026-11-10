package com.foxhole.vpn.ui

import android.graphics.drawable.Drawable
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnAppRoute
import com.foxhole.vpn.VpnAppRouting
import com.foxhole.vpn.VpnAppRule
import com.foxhole.vpn.VpnRoutingMode
import com.foxhole.vpn.VpnScenarioAppFilter
import com.foxhole.vpn.vpnScenarioAppVisible
import com.foxhole.vpn.routingChangeNeedsConfirmation
import com.foxhole.vpn.withRule
import com.foxhole.vpn.withWebRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal class VpnScenariosUiState {
    val listState = LazyListState()
    val webListState = LazyListState()
    var webView by mutableStateOf(false)
}

@Composable
internal fun VpnScenariosSheet(
    routing: VpnAppRouting,
    enabled: Boolean,
    busy: Boolean,
    ui: VpnScenariosUiState,
    onChange: ((VpnAppRouting) -> VpnAppRouting) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    var pending by remember { mutableStateOf<Pair<VpnAppRouting, VpnAppRouting>?>(null) }
    val haptic = LocalHapticFeedback.current
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showWebsiteInput by rememberSaveable { mutableStateOf(false) }
    fun changeScenario(target: VpnAppRouting) {
        if (routingChangeNeedsConfirmation(routing, target)) pending = routing to target
        else onChange { current ->
            current.copy(mode = target.mode, defaultThroughVpn = target.defaultThroughVpn)
        }
    }
    VpnSheetContent(R.string.vpn_scenarios, R.drawable.lin_apps, busy = busy,
        compactHeader = true, scrollableBody = true, footer = {
        val addColor = routerConnectColor()
        OutlinedButton(onClick = { if (ui.webView) showWebsiteInput = true else showPicker = true }, enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) addColor else LocalVpnColors.current.border),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = addColor),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 56.dp).testTag("vpn-add-application")) {
            Icon(painterResource(R.drawable.lin_plus), null, Modifier.size(20.dp))
            Text(stringResource(if (ui.webView) R.string.vpn_add_website else R.string.vpn_add_application), Modifier.padding(start = 8.dp))
        }
    }, actions = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); ui.webView = !ui.webView },
                enabled = !busy, modifier = Modifier.size(48.dp).testTag("vpn-routing-view")) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(painterResource(if (ui.webView) R.drawable.lin_apps else R.drawable.lin_world_www),
                    stringResource(if (ui.webView) R.string.vpn_routing_view_applications else R.string.vpn_routing_view_websites), Modifier.size(24.dp))
            }
            if (ui.webView) VpnRouterHelpButton(R.string.vpn_web_rules, R.string.vpn_web_rules_help,
                actionStyle = true, helpContent = { VpnWebsiteMaskHelp() })
            else VpnRouterHelpButton(R.string.vpn_app_rules, R.string.vpn_scenarios_help,
                actionStyle = true, helpContent = { VpnApplicationRoutingHelp() })
            IconButton(onClick = onSettings, enabled = !busy, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.vpn_scenario_settings))
            }
        }
    }) {
        if (ui.webView) {
            VpnWebsiteRules(routing, enabled, ui.webListState, onChange)
        } else {
        ScenarioOutlinedGroup {
            Row(Modifier.fillMaxWidth().offset(y = (-1).dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.lin_apps), null, Modifier.size(20.dp))
                Text(stringResource(R.string.vpn_routing_mode), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                ScenarioDropdown(stringResource(routingModeLabel(routing.mode)), enabled,
                    Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * .62f)
                        .testTag("vpn-scenario-mode"), icon = scenarioModeIcon(routing.mode)) {
                    VpnRoutingMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(stringResource(routingModeLabel(mode)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(painterResource(scenarioModeIcon(mode)), null, Modifier.size(20.dp)) }, onClick = {
                            dismiss()
                            if (mode != routing.mode) changeScenario(routing.copy(mode = mode))
                        })
                    }
                }
            }
            ScenarioDivider()
            ScenarioToggle(R.string.vpn_scenario_whole_device, R.string.vpn_scenario_default_help,
                checked = routing.mode == VpnRoutingMode.WHOLE_DEVICE || routing.defaultThroughVpn,
                enabled = enabled && routing.mode == VpnRoutingMode.SELECTED_APPS,
                tag = "vpn-scenario-default", icon = R.drawable.router_device_phone) { changeScenario(routing.copy(defaultThroughVpn = it)) }
        }
        Row(Modifier.padding(top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.lin_apps), null, Modifier.size(20.dp))
            Text(stringResource(R.string.vpn_app_rules), style = MaterialTheme.typography.titleSmall)
        }
        if (routing.rules.isEmpty()) {
            ScenarioEmptyState(stringResource(R.string.vpn_app_rules_empty))
        } else {
            val listHeight = minOf((routing.rules.size * 80).dp, LocalConfiguration.current.screenHeightDp.dp * .85f)
            Surface(Modifier.heightIn(max = listHeight).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp), color = LocalVpnColors.current.panel,
                border = BorderStroke(1.dp, LocalVpnColors.current.border)) {
                Box(Modifier.fillMaxWidth().clipToBounds()) {
                    LazyColumn(Modifier.fillMaxWidth().testTag("vpn-app-rules").nestedScroll(ScenarioListScrollConnection),
                        state = ui.listState) {
                        itemsIndexed(routing.rules, key = { _, rule -> rule.packageName }) { index, rule ->
                            ScenarioAppRow(rule, enabled,
                                onRoute = { route -> onChange { it.withRule(rule.packageName, route) } },
                                onRemove = { label -> removing = rule.packageName to label })
                            if (index < routing.rules.lastIndex) ScenarioDivider()
                        }
                    }
                    VpnProfileScrollbar(ui.listState, Modifier.matchParentSize(), "vpn-app-rules-scrollbar")
                }
            }
        }
        }
    }
    if (showWebsiteInput) {
        VpnWebsiteInputSheet(enabled, onDismiss = { showWebsiteInput = false }) { mask, route ->
            onChange { it.withWebRule(mask, route) }
            showWebsiteInput = false
        }
    }
    if (showPicker) {
        VpnBottomSheet(onDismiss = { showPicker = false },
            confineContentGestures = true) {
            ScenarioApplicationPicker(routing, enabled, onChange,
                onRemove = { packageName, label -> removing = packageName to label }, onSystemPicker = {
                showPicker = false
                onAdd()
            })
        }
    }
    removing?.let { (packageName, label) ->
        VpnBottomSheet(onDismiss = { removing = null }, showCloseButton = false) {
            VpnSheetContent(R.string.vpn_scenario_delete_title, R.drawable.lin_trash) {
                Text(stringResource(R.string.vpn_scenario_delete_body, label),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { removing = null }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_cancel))
                    }
                    Button(enabled = enabled, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        removing = null
                        onChange { current -> current.copy(rules = current.rules.filterNot { it.packageName == packageName }) }
                    }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_scenario_confirm_action))
                    }
                }
            }
        }
    }
    pending?.let { (before, target) ->
        VpnBottomSheet(onDismiss = { pending = null }, showCloseButton = false) {
            VpnSheetContent(R.string.vpn_scenario_confirm_title, R.drawable.lin_apps) {
                Text(stringResource(R.string.vpn_scenario_confirm_body),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pending = null }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_cancel))
                    }
                    Button(enabled = enabled, onClick = {
                        pending = null
                        onChange { current ->
                            check(current.mode == before.mode && current.defaultThroughVpn == before.defaultThroughVpn) { "Scenario changed before confirmation" }
                            current.copy(mode = target.mode, defaultThroughVpn = target.defaultThroughVpn)
                        }
                    }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.vpn_scenario_confirm_action))
                    }
                }
            }
        }
    }
}

@Composable
internal fun VpnScenarioSettingsSheet(
    routing: VpnAppRouting,
    enabled: Boolean,
    busy: Boolean,
    onChange: ((VpnAppRouting) -> VpnAppRouting) -> Unit,
    onBack: () -> Unit,
) {
    VpnSheetContent(R.string.vpn_scenario_settings, R.drawable.lin_settings,
        Modifier.verticalScroll(rememberScrollState()), busy = busy) {
        ScenarioOutlinedGroup {
            ScenarioToggle(R.string.vpn_scenario_atomic, R.string.vpn_scenario_atomic_help,
                routing.atomicApply, enabled, "vpn-scenario-atomic") { value -> onChange { it.copy(atomicApply = value) } }
            ScenarioDivider()
            ScenarioToggle(R.string.vpn_scenario_confirmation, R.string.vpn_scenario_confirmation_help,
                routing.confirmChanges, enabled, "vpn-scenario-confirmation") { value -> onChange { it.copy(confirmChanges = value) } }
        }
    }
}

@Composable
private fun ScenarioToggle(title: Int, help: Int, checked: Boolean, enabled: Boolean, tag: String, icon: Int? = null, onChange: (Boolean) -> Unit) {
    val label = stringResource(title)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.padding(end = 8.dp).size(20.dp))
        VpnHelpLabel(label, title, help, Modifier.weight(1f).padding(end = 8.dp),
            color = if (enabled) LocalVpnColors.current.foreground else LocalVpnColors.current.muted)
        VpnSwitch(checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
}

private class ScenarioMenuScope(val dismiss: () -> Unit)

@Composable
private fun ScenarioDropdown(value: String, enabled: Boolean, modifier: Modifier = Modifier, icon: Int? = null,
    alignRight: Boolean = false,
    menu: @Composable ScenarioMenuScope.() -> Unit) {
    VpnChoiceDropdown(value, enabled, modifier, leadingIcon = icon, alignRight = alignRight) { dismiss ->
        ScenarioMenuScope(dismiss).menu()
    }
}

private data class ScenarioAppDetails(val label: String, val icon: ImageBitmap?)

@Composable
private fun ScenarioAppRow(rule: VpnAppRule, enabled: Boolean, onRoute: (VpnAppRoute) -> Unit, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    fun remove(label: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onRemove(label)
    }
    val details by produceState(ScenarioAppDetails(rule.packageName, null), rule.packageName, context) {
        value = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            try {
                val info = manager.getApplicationInfo(rule.packageName, 0)
                ScenarioAppDetails(manager.getApplicationLabel(info).toString(),
                    manager.getApplicationIcon(info).scenarioBitmap())
            } catch (_: PackageManager.NameNotFoundException) { ScenarioAppDetails(rule.packageName, null) }
            catch (error: CancellationException) { throw error }
            catch (_: RuntimeException) { ScenarioAppDetails(rule.packageName, null) }
        }
    }
    val colors = LocalVpnColors.current
    val removeLabel = stringResource(R.string.vpn_scenario_delete_title)
    Surface(Modifier.fillMaxWidth().pointerInput(enabled, details.label) {
        if (enabled) detectTapGestures(onLongPress = { remove(details.label) })
    }.semantics { if (enabled) onLongClick(removeLabel) { remove(details.label); true } },
        color = colors.panel, contentColor = colors.foreground) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon = details.icon
            if (icon != null) Image(icon, null, Modifier.size(32.dp))
            else Icon(painterResource(R.drawable.lin_apps), null, Modifier.size(32.dp), tint = colors.muted)
            Text(details.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ScenarioDropdown(stringResource(scenarioRouteLabel(rule.route)),
                enabled, Modifier.widthIn(max = 176.dp).heightIn(min = 48.dp).testTag("vpn-app-route-${rule.packageName}"),
                icon = scenarioRouteIcon(rule.route), alignRight = true) {
                VpnAppRoute.entries.filter { it != VpnAppRoute.BLOCK || android.os.Build.VERSION.SDK_INT >= 29 }.forEach { route ->
                    DropdownMenuItem(text = { Text(stringResource(scenarioRouteLabel(route)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(painterResource(scenarioRouteIcon(route)), null, Modifier.size(20.dp)) },
                        onClick = { dismiss(); if (route != rule.route) onRoute(route) })
                }
            }
        }
    }
}

private fun Drawable.scenarioBitmap(): ImageBitmap = toBitmap(64, 64).asImageBitmap()

internal fun scenarioRouteIcon(route: VpnAppRoute): Int = when (route) {
    VpnAppRoute.VPN -> R.drawable.lin_power
    VpnAppRoute.DIRECT -> R.drawable.router_review_globe
    VpnAppRoute.BLOCK -> R.drawable.lin_ban
}

internal fun scenarioRouteLabel(route: VpnAppRoute): Int = when (route) {
    VpnAppRoute.VPN -> R.string.vpn_app_route_vpn
    VpnAppRoute.DIRECT -> R.string.vpn_app_route_direct
    VpnAppRoute.BLOCK -> R.string.vpn_app_route_block
}

private fun scenarioModeIcon(mode: VpnRoutingMode): Int = when (mode) {
    VpnRoutingMode.WHOLE_DEVICE -> R.drawable.router_device_phone
    VpnRoutingMode.SELECTED_APPS -> R.drawable.lin_apps
}

@Composable
private fun ScenarioOutlinedGroup(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalVpnColors.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.panel,
        contentColor = colors.foreground, border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content = content)
    }
}

@Composable
internal fun ScenarioDivider(dotted: Boolean = false) {
    val color = LocalVpnColors.current.border
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
            pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())) else null)
    }
}

@Composable
internal fun ScenarioEmptyState(message: String, modifier: Modifier = Modifier) {
    val colors = LocalVpnColors.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.panel,
        border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.lin_info), null, Modifier.size(24.dp), tint = colors.muted)
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                color = colors.muted)
        }
    }
}

private data class ScenarioInstalledApp(val packageName: String, val label: String, val system: Boolean?)
private data class ScenarioAppInventory(val applications: List<ScenarioInstalledApp>, val failed: Boolean = false)

@Composable
private fun ScenarioApplicationPicker(
    routing: VpnAppRouting,
    enabled: Boolean,
    onChange: ((VpnAppRouting) -> VpnAppRouting) -> Unit,
    onRemove: (String, String) -> Unit,
    onSystemPicker: () -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(VpnScenarioAppFilter.USER) }
    val listState = remember { LazyListState() }
    val inventory by produceState<ScenarioAppInventory?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try {
                val manager = context.packageManager
                val installed = if (Build.VERSION.SDK_INT >= 33) {
                    manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    manager.getInstalledApplications(0)
                }
                ScenarioAppInventory(installed.asSequence()
                    .filter { it.packageName != context.packageName }
                    .distinctBy { it.packageName }
                    .map { info ->
                        val label = try { manager.getApplicationLabel(info).toString().ifBlank { info.packageName } }
                            catch (_: RuntimeException) { info.packageName }
                        ScenarioInstalledApp(info.packageName, label,
                            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    }.toList())
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                ScenarioAppInventory(emptyList(), failed = true)
            }
        }
    }
    val selected = remember(routing.rules) { routing.rules.mapTo(mutableSetOf()) { it.packageName } }
    val available = remember(inventory, selected, context.packageName) {
        val apps = inventory?.applications.orEmpty().associateBy { it.packageName }.toMutableMap()
        selected.filter { it != context.packageName }.forEach { packageName ->
            apps.putIfAbsent(packageName, ScenarioInstalledApp(packageName, packageName, null))
        }
        apps.values.sortedWith(compareByDescending<ScenarioInstalledApp> { it.packageName in selected }
            .thenBy { it.label.lowercase() }.thenBy { it.packageName })
    }
    val visible = remember(available, selected, filter) {
        available.filter { it.packageName in selected || vpnScenarioAppVisible(it.system, filter) }
    }
    VpnRouterSheetContent(R.string.vpn_add_application, R.drawable.lin_apps, scrollable = false,
        footer = {
            ScenarioOutlinedGroup {
                VpnChoiceDropdown(stringResource(R.string.vpn_scenario_filter_count,
                    stringResource(scenarioFilterLabel(filter)), visible.size), enabled,
                    Modifier.fillMaxWidth().testTag("vpn-scenario-app-filter"),
                    leadingIcon = R.drawable.lin_apps, menuMaxHeight = 320.dp,
                    tag = "vpn-scenario-app-filter", fullWidth = true, preferAbove = true) { dismiss ->
                    VpnScenarioAppFilter.entries.forEach { option ->
                        val count = available.count { it.packageName in selected || vpnScenarioAppVisible(it.system, option) }
                        DropdownMenuItem(text = {
                            Text(stringResource(R.string.vpn_scenario_filter_count,
                                stringResource(scenarioFilterLabel(option)), count),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }, onClick = { dismiss(); filter = option }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }) {
        if (inventory == null && visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        } else {
            if (inventory?.failed == true) {
                ScenarioEmptyState(stringResource(R.string.vpn_scenario_picker_failed))
                OutlinedButton(onClick = onSystemPicker, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.vpn_scenario_system_picker))
                }
            }
            if (visible.isEmpty()) {
                ScenarioEmptyState(stringResource(R.string.vpn_scenario_no_visible_apps))
            } else {
                Box(Modifier.fillMaxWidth().clipToBounds()) {
                    LazyColumn(Modifier.fillMaxWidth().nestedScroll(ScenarioListScrollConnection)
                        .testTag("vpn-scenario-app-picker"), state = listState) {
                        itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
                            ScenarioPickerAppRow(app, app.packageName in selected, enabled) { checked ->
                                if (checked) {
                                    onChange { current ->
                                        if (current.rules.any { it.packageName == app.packageName }) current
                                        else current.withRule(app.packageName, VpnAppRoute.VPN)
                                    }
                                } else onRemove(app.packageName, app.label)
                            }
                            if (index < visible.lastIndex) ScenarioDivider(dotted = true)
                        }
                    }
                    VpnProfileScrollbar(listState, Modifier.matchParentSize(), "vpn-scenario-picker-scrollbar")
                }
            }
        }
    }
}

@Composable
private fun ScenarioPickerAppRow(app: ScenarioInstalledApp, selected: Boolean, enabled: Boolean,
    onSelected: (Boolean) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val select: (Boolean) -> Unit = { value ->
        if (enabled && value != selected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onSelected(value)
        }
    }
    val icon by produceState<ImageBitmap?>(null, context, app.packageName) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(app.packageName).scenarioBitmap() }
            catch (_: PackageManager.NameNotFoundException) { null }
            catch (_: RuntimeException) { null }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .clickable(enabled = enabled, role = Role.Checkbox) { select(!selected) }
        .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, null, Modifier.size(32.dp))
        else Icon(painterResource(R.drawable.lin_apps), null, Modifier.size(32.dp), tint = LocalVpnColors.current.muted)
        Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        VpnSwitch(selected, onCheckedChange = select, enabled = enabled,
            modifier = Modifier.testTag("vpn-scenario-app-${app.packageName}").semantics { contentDescription = app.label })
    }
}

private fun scenarioFilterLabel(filter: VpnScenarioAppFilter): Int = when (filter) {
    VpnScenarioAppFilter.ALL -> R.string.vpn_scenario_filter_all
    VpnScenarioAppFilter.SYSTEM -> R.string.vpn_scenario_filter_system
    VpnScenarioAppFilter.USER -> R.string.vpn_scenario_filter_user
}

internal fun routingModeLabel(mode: VpnRoutingMode): Int = when (mode) {
    VpnRoutingMode.WHOLE_DEVICE -> R.string.vpn_scenario_whole_device
    VpnRoutingMode.SELECTED_APPS -> R.string.vpn_scenario_selected_apps
}

internal object ScenarioListScrollConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}
