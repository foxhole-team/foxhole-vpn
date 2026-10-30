package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.router.RouterInstallCatalog
import com.foxhole.vpn.router.RouterInstallProtocol
import java.util.Locale

@Composable
internal fun VpnRouterProtocolDropdown(
    catalog: RouterInstallCatalog?,
    selected: Set<RouterInstallProtocol>,
    enabled: Boolean,
    onSelect: (Set<RouterInstallProtocol>) -> Unit,
) {
    var expanded by remember(catalog?.sha256) { mutableStateOf(false) }
    val empty = stringResource(R.string.vpn_router_choose_protocols)
    val summary = RouterInstallProtocol.entries.filter { it in selected }.joinToString(" · ") { it.label }
        .ifBlank { empty }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        VpnRouterDropdownButton(summary, enabled && catalog != null) { expanded = !expanded }
        DropdownMenu(
            expanded = expanded && enabled && catalog != null,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 320.dp),
        ) {
            catalog?.availableProtocols?.forEach { protocol ->
                val checked = protocol in selected
                DropdownMenuItem(
                    text = { Text(protocol.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Checkbox(checked, onCheckedChange = null) },
                    onClick = { onSelect(if (checked) selected - protocol else selected + protocol) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
internal fun VpnRouterProfileDropdown(
    profiles: List<VpnProfileSummary>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.vpn_router_no_profile)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        VpnRouterDropdownButton(profiles.firstOrNull { it.id == selectedId }?.name ?: none, enabled) {
            expanded = !expanded
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 320.dp),
        ) {
            DropdownMenuItem(text = { Text(none) }, onClick = { expanded = false; onSelect(null) })
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(profile.id) },
                )
            }
        }
    }
}

@Composable
internal fun VpnRouterCountryFlag(code: String, enabled: Boolean, onSelect: (String) -> Unit,
    autoSelected: Boolean = false, onAuto: (() -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(locale) {
        Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(locale) }.sortedBy { it.second }
    }
    val country = countries.firstOrNull { it.first == code }
    val description = if (country == null) stringResource(R.string.router_review_country_unknown_description)
        else stringResource(R.string.router_review_country_description, country.second)
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
            RouterCountryAsset(code)
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 300.dp).heightIn(max = 320.dp),
        ) {
            if (onAuto != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.router_review_country_auto)) },
                leadingIcon = { Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(16.dp)) },
                trailingIcon = { Checkbox(autoSelected, onCheckedChange = null) },
                onClick = { expanded = false; onAuto() },
            )
            countries.forEach { (iso, name) ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { RouterCountryAsset(iso) },
                    onClick = { expanded = false; onSelect(iso) },
                )
            }
        }
    }
}

@Composable
private fun RouterCountryAsset(country: String) {
    VpnFlagIcon(country, MaterialTheme.typography.bodyMedium, showUnknown = true)
}

@Composable
private fun VpnRouterDropdownButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.lin_chevron_down), null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun VpnRouterDeviceRouteDropdown(route: String?, enabled: Boolean, vpnAvailable: Boolean, onSelect: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(when (route) {
        "vpn" -> R.string.router_review_device_vpn
        "direct" -> R.string.router_review_direct
        else -> R.string.router_review_router_default
    })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        VpnRouterDropdownButton(label, enabled) { expanded = !expanded }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 240.dp)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.router_review_device_vpn)) },
                leadingIcon = { Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp)) },
                enabled = vpnAvailable, onClick = { expanded = false; onSelect(true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.router_review_direct)) },
                leadingIcon = { Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(20.dp)) },
                onClick = { expanded = false; onSelect(false) })
        }
    }
}
