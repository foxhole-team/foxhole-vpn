package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
import com.foxhole.vpn.router.RouterInstallCatalog
import com.foxhole.vpn.router.RouterDiscoveryKind
import com.foxhole.vpn.router.RouterDiscoveryIssue
import com.foxhole.vpn.router.RouterDiscoveryResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnRouterEntrySheet(
    onSetup: () -> Unit,
    onPair: () -> Unit,
    setupEnabled: Boolean = true,
    canInstallRouter: Boolean = false,
    canInspectRouter: Boolean = true,
    installationCatalog: RouterInstallCatalog? = null,
    onFind: (() -> Unit)? = null,
    discovery: RouterDiscoveryResult? = null,
    finding: Boolean = false,
    onConnect: ((Boolean) -> Unit)? = null,
    onCancelDiscovery: (() -> Unit)? = null,
    wifiConnected: Boolean? = null,
) {
    val observedWifi by rememberRouterWifiConnected()
    val connected = wifiConnected ?: observedWifi
    val notFound = discovery?.kind == RouterDiscoveryKind.NOT_FOUND
    VpnRouterSheetContent(
        when (discovery?.kind) {
            RouterDiscoveryKind.INSTALLED -> R.string.router_discovery_ready_to_pair
            RouterDiscoveryKind.SSH_AVAILABLE -> R.string.router_review_router_found
            else -> R.string.router_review_management
        },
        R.drawable.lin_router,
        titleMaxLines = 2,
        busy = finding,
        titleHelp = R.string.router_help_setup_pair,
        titleHelpContent = { VpnHelpBlocks(vpnRouterHelpBlocks(canInstallRouter, canInspectRouter), scope = "router-overview") },
        footer = {
            when (discovery?.kind) {
                RouterDiscoveryKind.INSTALLED, RouterDiscoveryKind.SSH_AVAILABLE -> Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(colors = vpnNeutralOutlinedButtonColors(), onClick = { onCancelDiscovery?.invoke() },
                        enabled = !finding && onCancelDiscovery != null,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.router_discovery_cancel))
                    }
                    if (discovery?.kind == RouterDiscoveryKind.INSTALLED) OutlinedButton(
                        onClick = { onConnect?.invoke(false) ?: onPair() },
                        enabled = !finding,
                        border = BorderStroke(1.5.dp, routerConnectColor()),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = routerConnectColor()),
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.router_discovery_continue))
                    } else OutlinedButton(colors = vpnNeutralOutlinedButtonColors(), onClick = onSetup,
                        enabled = !finding && setupEnabled, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.router_discovery_continue))
                    }
                }
                else -> {
                    OutlinedButton(colors = vpnNeutralOutlinedButtonColors(), onClick = { onFind?.invoke() }, enabled = !finding && connected && onFind != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(if (notFound) R.string.router_review_retry_search
                            else R.string.router_review_find_router))
                    }

                }
            }
            OutlinedButton(colors = vpnNeutralOutlinedButtonColors(), onClick = LocalVpnSheetClose.current,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.vpn_close))
            }
        },
    ) {

        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val illustrationHeight = if (windowHeight < 600.dp) 120.dp else 176.dp
        Box(Modifier.fillMaxWidth().height(illustrationHeight), contentAlignment = Alignment.Center) {
            VpnRouterIllustration(Modifier.fillMaxSize(), searching = finding,
                connected = discovery?.kind == RouterDiscoveryKind.INSTALLED)
        }
        RouterWifiIndicator(connected)
        if (discovery?.kind == RouterDiscoveryKind.INSTALLED || discovery?.kind == RouterDiscoveryKind.SSH_AVAILABLE) {

            if (discovery.kind == RouterDiscoveryKind.SSH_AVAILABLE) Text(
                stringResource(R.string.router_discovery_setup_question), style = MaterialTheme.typography.bodyMedium)
        } else {
            if (discovery == null) Text(stringResource(if (canInstallRouter) R.string.vpn_router_intro else if (canInspectRouter) R.string.help_router_install_unavailable else R.string.help_router_install_manual), style = MaterialTheme.typography.bodyMedium)
            else {
                val discoveryMessage = stringResource(discovery.issue?.let { issue ->
                    when (issue) {
                        RouterDiscoveryIssue.API_UNAVAILABLE -> if (discovery.kind == RouterDiscoveryKind.SSH_AVAILABLE)
                            R.string.router_discovery_ssh_only else R.string.router_discovery_api_absent
                        RouterDiscoveryIssue.TIMEOUT -> R.string.router_discovery_timeout
                        RouterDiscoveryIssue.TLS -> R.string.router_discovery_tls
                        RouterDiscoveryIssue.NETWORK -> R.string.router_discovery_network
                        RouterDiscoveryIssue.PACKAGE_STATUS -> R.string.router_discovery_package_check
                    }
                } ?: when (discovery.kind) {
                    RouterDiscoveryKind.INSTALLED -> R.string.router_review_package_present
                    RouterDiscoveryKind.SSH_AVAILABLE -> if (canInstallRouter) R.string.router_review_setup_available else if (canInspectRouter) R.string.help_router_install_unavailable else R.string.help_router_install_manual
                    RouterDiscoveryKind.UNKNOWN -> R.string.router_review_package_unknown
                    RouterDiscoveryKind.NOT_FOUND -> R.string.router_review_not_found
                })
                Text(discoveryMessage, style = MaterialTheme.typography.bodyMedium)
                if (discovery.kind == RouterDiscoveryKind.UNKNOWN || discovery.kind == RouterDiscoveryKind.NOT_FOUND) {

                }
            }
            Text(stringResource(R.string.vpn_router_local_network_purpose), style = MaterialTheme.typography.bodySmall,
                color = LocalVpnColors.current.muted)
            if (!notFound) {
                Text(stringResource(R.string.router_setup_compatibility_title), style = MaterialTheme.typography.titleMedium)
                val versions = (installationCatalog?.variants.orEmpty().flatMap { variant ->
                    variant.supportedOpenWrt.map { it to variant.supportedTargets }
                } + installationCatalog?.compactPlatforms.orEmpty().flatMap { platform ->
                    platform.supportedOpenWrt.map { it to platform.supportedTargets }
                }).groupBy({ it.first }, { it.second }).toSortedMap()
                if (versions.isEmpty()) {
                    Text(stringResource(R.string.router_review_supported_platforms), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.router_review_catalog_pending), style = MaterialTheme.typography.bodySmall)
                }
                else VpnRouterReviewTable(versions.map { (version, targets) -> "OpenWrt $version" to targets.flatten().distinct().sorted().joinToString(" · ") })
            }
        }
    }
}
