package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foxhole.vpn.router.routerLanDeviceUsesVpn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.router.RouterInstallCatalog
import com.foxhole.vpn.router.RouterInstallProtocol
import java.util.Locale

@Composable
internal fun VpnChoiceDropdown(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    leadingIcon: Int? = null,
    labelColor: Color? = null,
    menuMaxHeight: Dp = 320.dp,
    labelStyle: TextStyle? = null,
    tag: String? = null,
    fullWidth: Boolean = false,
    preferAbove: Boolean = false,
    leadingFlagCountry: String? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val menuMaxWidth = (windowWidth - 32.dp).coerceAtLeast(48.dp)
    val anchorWidth = with(density) { anchorWidthPx.toDp() }.coerceIn(48.dp, menuMaxWidth)
    val colors = LocalVpnColors.current
    val shape = RoundedCornerShape(24.dp)
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = !expanded }, enabled = enabled, shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.panel,
                contentColor = labelColor ?: colors.foreground),
            border = BorderStroke(1.dp, colors.border),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.heightIn(min = 40.dp).widthIn(max = menuMaxWidth)
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .onSizeChanged { anchorWidthPx = it.width }
                .then(if (tag != null) Modifier.testTag("$tag-anchor") else Modifier),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (leadingFlagCountry != null) VpnFlagIcon(leadingFlagCountry, labelStyle ?: MaterialTheme.typography.bodyMedium)
                else if (leadingIcon != null) Icon(painterResource(leadingIcon), null, Modifier.size(16.dp))
                Text(label, Modifier.weight(1f, fill = false), style = labelStyle ?: MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.lin_chevron_down), null, Modifier.size(16.dp))
            }
        }
        if (preferAbove) {
            if (expanded && enabled) Popup(
                popupPositionProvider = remember(density) { AboveChoicePosition(with(density) { 16.dp.roundToPx() }) },
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(shape = shape, color = colors.panel, contentColor = colors.foreground,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.width(anchorWidth).heightIn(max = menuMaxHeight)
                        .then(if (tag != null) Modifier.testTag("$tag-menu") else Modifier)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        content.invoke(this, { expanded = false })
                    }
                }
            }
        } else DropdownMenu(
            expanded = expanded && enabled, onDismissRequest = { expanded = false }, shape = shape,
            containerColor = colors.panel, tonalElevation = 0.dp, shadowElevation = 0.dp,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.then(if (fullWidth) Modifier.width(anchorWidth) else Modifier.widthIn(min = anchorWidth, max = menuMaxWidth))
                .heightIn(max = menuMaxHeight)
                .then(if (tag != null) Modifier.testTag("$tag-menu") else Modifier),
        ) { content.invoke(this, { expanded = false }) }
    }
}

private class AboveChoicePosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val maxX = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val maxY = (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)
        return IntOffset(left.coerceIn(margin, maxX),
            (anchorBounds.top - popupContentSize.height).coerceIn(margin, maxY))
    }
}

@Composable
internal fun VpnRouterProtocolDropdown(
    catalog: RouterInstallCatalog?,
    selected: Set<RouterInstallProtocol>,
    enabled: Boolean,
    onSelect: (Set<RouterInstallProtocol>) -> Unit,
) {
    val empty = stringResource(R.string.vpn_router_choose_protocols)
    val summary = RouterInstallProtocol.entries.filter { it in selected }.joinToString(" · ") { it.label }
        .ifBlank { empty }
    VpnChoiceDropdown(summary, enabled && catalog != null, leadingIcon = R.drawable.lin_power) {
            catalog?.availableProtocols?.forEach { protocol ->
                val checked = protocol in selected
                DropdownMenuItem(
                    text = { Text(protocol.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Checkbox(checked, onCheckedChange = null) },
                    onClick = { onSelect(if (checked) selected - protocol else selected + protocol) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
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
    val none = stringResource(R.string.vpn_router_no_profile)
    VpnChoiceDropdown(profiles.firstOrNull { it.id == selectedId }?.name ?: none, enabled,
        leadingIcon = R.drawable.lin_profiles) { dismiss ->
            DropdownMenuItem(text = { Text(none, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { dismiss(); onSelect(null) })
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { dismiss(); onSelect(profile.id) },
                )
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
    val normalizedCode = code.trim().uppercase(Locale.ROOT)
    val country = countries.firstOrNull { it.first == normalizedCode }
    val description = if (country == null) stringResource(R.string.router_review_country_unknown_description)
        else stringResource(R.string.router_review_country_description, country.second)
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
            RouterCountryAsset(normalizedCode, compact = true)
        }
        if (expanded && enabled) VpnBottomSheet(onDismiss = { expanded = false },
            heightFraction = .5f, confineContentGestures = true) {
            VpnSheetContent(R.string.router_control_choose_country, R.drawable.router_country_unknown) {
                LazyColumn(Modifier.fillMaxSize().testTag("router-country-list")) {
                    if (onAuto != null) item {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.router_review_country_auto), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { RouterCountryAsset(normalizedCode) },
                            trailingIcon = { Checkbox(autoSelected, onCheckedChange = null) },
                            onClick = { expanded = false; onAuto() },
                        )
                        HorizontalDivider(color = LocalVpnColors.current.border)
                    }
                    items(countries, key = { it.first }) { (iso, name) ->
                        DropdownMenuItem(
                            text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { RouterCountryAsset(iso) },
                            onClick = { expanded = false; onSelect(iso) },
                        )
                        HorizontalDivider(color = LocalVpnColors.current.border)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterCountryAsset(country: String, compact: Boolean = false) {
    VpnFlagIcon(country, MaterialTheme.typography.bodyMedium, showUnknown = true,
        iconSize = if (compact) androidx.compose.ui.unit.DpSize(24.dp, 16.dp) else androidx.compose.ui.unit.DpSize(30.dp, 20.dp))
}

@Composable
internal fun VpnRouterDeviceRouteDropdown(route: String?, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val usesVpn = routerLanDeviceUsesVpn(route)
    val label = stringResource(if (usesVpn) R.string.router_review_device_vpn
        else R.string.router_review_direct)
    VpnChoiceDropdown(label, enabled,
        leadingIcon = if (usesVpn) R.drawable.lin_power else R.drawable.router_review_globe,
        labelColor = if (usesVpn) routerConnectColor() else null, menuMaxHeight = 240.dp) { dismiss ->
            DropdownMenuItem(text = { Text(stringResource(R.string.router_review_device_vpn), color = routerConnectColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp), tint = routerConnectColor()) },
                onClick = { dismiss(); onSelect(true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.router_review_direct), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(20.dp)) },
                onClick = { dismiss(); onSelect(false) })
    }
}
