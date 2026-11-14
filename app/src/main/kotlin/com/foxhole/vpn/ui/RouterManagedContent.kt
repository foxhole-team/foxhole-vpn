package com.foxhole.vpn.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.router.*


@Composable
internal fun RouterVpnCombinedHelp() {
    VpnHelpBlocks(listOf(
        HelpBlock.Heading(R.string.router_review_profiles, R.drawable.lin_profiles),
        HelpBlock.Paragraph(R.string.help_router_transfer),
        HelpBlock.Paragraph(R.string.router_control_vpn_help),
        HelpBlock.Heading(R.string.router_control_lan_clients, R.drawable.lin_router),
        HelpBlock.Paragraph(R.string.router_control_lan_help),
    ), scope = "router-vpn-and-devices")
}

@Composable
internal fun RouterSettingsCombinedHelp() {
    VpnHelpBlocks(listOf(
        HelpBlock.Heading(R.string.router_control_enable, R.drawable.lin_settings), HelpBlock.Paragraph(R.string.router_control_enable_help),
        HelpBlock.Heading(R.string.router_home_server, R.drawable.lin_router), HelpBlock.Paragraph(R.string.router_home_server_help),
        HelpBlock.Heading(R.string.router_review_local_traffic, R.drawable.router_review_globe), HelpBlock.Paragraph(R.string.router_help_country_source),
        HelpBlock.Heading(R.string.router_smart_title, R.drawable.lin_router), HelpBlock.Paragraph(R.string.router_control_home_help),
        HelpBlock.Heading(R.string.router_control_home_vpn, R.drawable.lin_router), HelpBlock.Paragraph(R.string.router_control_home_vpn_help),
        HelpBlock.Heading(R.string.router_control_hide_offline, R.drawable.lin_eye_off), HelpBlock.Paragraph(R.string.router_control_hide_offline_help),
    ), scope = "router-settings")
}

@Composable
internal fun RouterAboutContent(state: RouterControlState) {
    val unknown = stringResource(R.string.router_about_unknown)
    val about = state.about?.takeIf { state.aboutPairingId == state.selectedRouterId }
    val countryCode = com.foxhole.vpn.router.routerDisplayCountryCode(state.preferences, state.routerCountryCode,
        state.snapshot?.countries).orEmpty()
    val country = countryCode.ifBlank { unknown }
    val localTraffic = routerCountryDirectControl(state.preferences, state.routerCountryCode, state.snapshot).checked
    val serverEnabled = routerVpnServerEnabled(state.snapshot)
    val rows = listOf(
        stringResource(R.string.router_about_package) to (about?.packageVersion ?: state.lastAuthenticatedPackageVersion ?: unknown),
        stringResource(R.string.router_about_firmware) to (about?.routerInfo?.firmwareVersion ?: unknown),
        stringResource(R.string.router_about_model) to (about?.routerInfo?.model ?: unknown),
        stringResource(R.string.router_about_country) to country,
        stringResource(R.string.router_about_storage) to (about?.storage ?: unknown),
        stringResource(R.string.router_about_ip) to (about?.routerInfo?.lanAddress ?: unknown),
    ) + (if (localTraffic) listOf(stringResource(R.string.router_about_local_traffic) to
        stringResource(R.string.router_setting_enabled)) else emptyList()) + listOf(
        stringResource(R.string.router_home_server) to stringResource(when (serverEnabled) {
            true -> R.string.router_setting_enabled
            false -> R.string.router_setting_disabled
            null -> R.string.router_about_unknown
        }),
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    VpnRouterReviewTable(rows, listOf(R.drawable.lin_file, R.drawable.lin_file, R.drawable.lin_router,
        R.drawable.router_review_globe, R.drawable.lin_settings, R.drawable.router_review_globe) +
        (if (localTraffic) listOf(R.drawable.router_review_globe) else emptyList()) + listOf(R.drawable.lin_router),
        countryCodes = countryCode.takeIf { it.matches(Regex("[A-Z]{2}")) }?.let { mapOf(3 to it) }.orEmpty())
    }
}

@Composable
internal fun RouterServerActionContent(state: RouterControlState, publicIpAck: Boolean, onPublicIpAck: (Boolean) -> Unit,
    onReadStatus: () -> Unit) {
    val plan = state.vpnServer.plan
    val job = state.vpnServer.job
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val info = Color(0xFF38A9D2)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (job != null) {
            if (job.running) {
                LinearProgressIndicator(progress = { job.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.router_server_job_running) + " " + job.progressPercent + "%")
                if (state.stage != RouterControlStage.CONNECTED) Text(stringResource(R.string.router_server_status_unknown),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stringResource(if (job.succeeded) R.string.router_server_job_done else R.string.router_server_job_failed),
                color = if (job.succeeded) routerConnectColor() else MaterialTheme.colorScheme.error)
            RouterManagedActionButton(stringResource(R.string.router_server_read_status), onReadStatus,
                Modifier.fillMaxWidth(), enabled = state.stage == RouterControlStage.CONNECTED && !state.busy)
        } else {
            Text(stringResource(when {
                plan?.available != true -> R.string.router_server_unavailable
                plan.action == "reuse" -> R.string.router_server_reuse
                else -> R.string.router_server_install
            }, plan?.componentVersion.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            if (plan?.downloadBytes != null) {
                Text(stringResource(R.string.router_server_download,
                    Formatter.formatFileSize(context, plan.downloadBytes)), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { uri.openUri("https://github.com/foxhole-team/foxhole-openwrt-client/releases") }) {
                Text("GitHub", color = info)
            }
            Surface(color = info.copy(alpha = .08f), border = BorderStroke(1.dp, info.copy(alpha = .65f)),
                shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.lin_info), null, Modifier.size(20.dp), tint = info)
                    Text(stringResource(R.string.router_server_public_ip), Modifier.weight(1f),
                        color = info, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (plan?.available == true) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = publicIpAck, onCheckedChange = onPublicIpAck)
                Text(stringResource(R.string.router_server_public_ack), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun RouterServerActionFooter(state: RouterControlState, publicIpAck: Boolean, onConfirm: () -> Unit,
    onBack: () -> Unit, onClose: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.vpnServer.job == null) RouterManagedActionButton(stringResource(R.string.router_server_enable), onConfirm,
            Modifier.fillMaxWidth(), enabled = !state.busy && state.vpnServer.plan?.available == true && publicIpAck, positive = true)
        VpnRouterNavigationFooter(!state.busy, onBack, onClose)
    }
}

@Composable
private fun RouterManagedActionButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true, positive: Boolean = false) {
    val color = if (positive) routerConnectColor() else LocalVpnColors.current.foreground
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        colors = vpnNeutralOutlinedButtonColors(), border = BorderStroke(1.dp, if (positive) color else LocalVpnColors.current.border)) {
        Text(label, color = color)
    }
}


@Composable
internal fun RouterConfirmationModal(title: Int, confirmLabel: Int, enabled: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit, icon: Int = R.drawable.lin_trash, destructive: Boolean = true, titleMaxLines: Int = 1,
    content: @Composable ColumnScope.() -> Unit) {
    VpnBottomSheet(onDismiss = onDismiss, showCloseButton = false, gesturesEnabled = !busy) {
        VpnRouterSheetContent(title, icon, busy = busy, titleMaxLines = titleMaxLines, footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, colors = vpnNeutralOutlinedButtonColors(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.vpn_cancel)) }
                if (confirmLabel == R.string.router_switch_profile) RouterPrimaryActionButton(
                    stringResource(confirmLabel), onConfirm, Modifier.weight(1f), enabled = enabled && !busy)
                else OutlinedButton(onClick = onConfirm, enabled = enabled && !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (destructive) vpnDeleteColor() else routerConnectColor()),
                    border = BorderStroke(1.dp, if (destructive) vpnDeleteColor() else routerConnectColor()), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(confirmLabel))
                }
            }
        }, content = content)
    }
}

@Composable
internal fun RouterVpnProfileChoiceContent(servers: List<kotlinx.serialization.json.JsonObject>, chosen: String?,
    enabled: Boolean, onChoose: (String) -> Unit) {
    if (servers.isEmpty()) Text(stringResource(R.string.router_review_no_profiles), style = MaterialTheme.typography.bodyMedium)
    servers.forEach { server ->
        val id = (server["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val name = (server["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val protocol = (server["protocol"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = { onChoose(id) }).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(chosen == id, onClick = null, enabled = enabled)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    VpnProtocolBadge(protocol, Modifier.padding(start = 6.dp).align(Alignment.Top).offset(y = (-5).dp))
                }
            }
        }
    }
}
