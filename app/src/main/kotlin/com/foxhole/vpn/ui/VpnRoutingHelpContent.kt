package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R

@Composable
internal fun VpnApplicationRoutingHelp() {
    val paragraphs = stringResource(R.string.vpn_scenarios_help).split("\n\n")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(R.string.site_routing_modes_title to paragraphs.take(3),
            R.string.site_routing_direct_title to paragraphs.drop(3).take(1),
            R.string.site_routing_block_title to paragraphs.drop(4).take(1)).forEach { (title, body) ->
            if (body.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                body.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        paragraphs.drop(5).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalVpnColors.current.muted) }
    }
}

@Composable
internal fun VpnWebsiteMaskHelp() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vpn_web_rules_help), style = MaterialTheme.typography.bodyMedium)
        Surface(border = BorderStroke(1.dp, LocalVpnColors.current.border), color = LocalVpnColors.current.panel,
            shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.site_mask_format), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.site_mask_scope), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                }
                listOf(
                    R.string.site_mask_exact_example to R.string.site_mask_exact_scope,
                    R.string.site_mask_suffix_example to R.string.site_mask_suffix_scope,
                    R.string.site_mask_zone_example to R.string.site_mask_zone_scope,
                    R.string.site_mask_url_example to R.string.site_mask_url_scope,
                    R.string.site_mask_idn_example to R.string.site_mask_idn_scope,
                    R.string.site_mask_ip_example to R.string.site_mask_ip_scope,
                    R.string.site_mask_cidr_example to R.string.site_mask_cidr_scope,
                    R.string.site_mask_range_example to R.string.site_mask_range_scope,
                ).forEach { (example, scope) ->
                    HorizontalDivider(color = LocalVpnColors.current.border)
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(example), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
                        Text(stringResource(scope), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text(stringResource(R.string.site_mask_unsupported_formats), style = MaterialTheme.typography.bodySmall,
            color = LocalVpnColors.current.muted)
    }
}
