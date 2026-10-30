package com.foxhole.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnFontStyle

internal enum class VpnSettingsDetail { THEME, LANGUAGE, BACKGROUND_UPDATES, HELP, ABOUT }

@Composable
internal fun VpnSettingsScreen(
    settings: VpnSettings,
    onSettingsDetail: (VpnSettingsDetail) -> Unit,
    onProfiles: () -> Unit,
    onSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onAppUpdate: () -> Unit = {},
) {
    VpnSheetContent(R.string.vpn_settings, R.drawable.lin_settings, modifier.verticalScroll(rememberScrollState())) {
        SectionLabel(R.string.vpn_settings_connection, first = true)
        SettingsRow(R.string.vpn_profiles, onClick = onProfiles)
        SettingsRow(R.string.vpn_system_settings, onClick = onSystemSettings)
        SettingsRow(R.string.vpn_background_updates) { onSettingsDetail(VpnSettingsDetail.BACKGROUND_UPDATES) }
        SectionLabel(R.string.vpn_settings_app)
        SettingsRow(R.string.vpn_theme, stringResource(themeLabel(settings.theme))) { onSettingsDetail(VpnSettingsDetail.THEME) }
        SettingsRow(R.string.vpn_language, stringResource(languageLabel(settings.language)),
            valueFlagCountry = com.foxhole.vpn.vpnLanguageFlagCountry(settings.language)) { onSettingsDetail(VpnSettingsDetail.LANGUAGE) }
        SettingsRow(R.string.vpn_app_update_title, onClick = onAppUpdate)
        SettingsRow(R.string.vpn_help) { onSettingsDetail(VpnSettingsDetail.HELP) }
        SettingsRow(R.string.vpn_about) { onSettingsDetail(VpnSettingsDetail.ABOUT) }
    }
}

@Composable
internal fun VpnSettingsDetailScreen(
    detail: VpnSettingsDetail,
    settings: VpnSettings,
    onSettings: (VpnSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val title = when (detail) {
        VpnSettingsDetail.THEME -> R.string.vpn_theme
        VpnSettingsDetail.LANGUAGE -> R.string.vpn_language
        VpnSettingsDetail.BACKGROUND_UPDATES -> R.string.vpn_background_updates
        VpnSettingsDetail.HELP -> R.string.vpn_help
        VpnSettingsDetail.ABOUT -> R.string.vpn_about
    }
    val icon = when (detail) {
        VpnSettingsDetail.THEME -> R.drawable.lin_theme
        VpnSettingsDetail.LANGUAGE -> R.drawable.lin_language
        VpnSettingsDetail.BACKGROUND_UPDATES -> R.drawable.lin_refresh
        VpnSettingsDetail.HELP -> R.drawable.lin_help
        VpnSettingsDetail.ABOUT -> R.drawable.lin_info
    }
    VpnSheetContent(title, icon, modifier.verticalScroll(rememberScrollState()), busy = busy) {
        when (detail) {
            VpnSettingsDetail.THEME -> {
                VpnTheme.entries.forEach { value ->
                    ChoiceRow(stringResource(themeLabel(value)), settings.theme == value, enabled) {
                        onSettings(settings.copy(theme = value))
                        onBack()
                    }
                }
                SectionLabel(R.string.vpn_font_style)
                FontStyleDropdown(settings.fontStyle, enabled) { onSettings(settings.copy(fontStyle = it)) }
            }
            VpnSettingsDetail.LANGUAGE -> com.foxhole.vpn.VPN_APP_LANGUAGE_CHOICES.forEach { value ->
                ChoiceRow(stringResource(languageLabel(value)), settings.language == value, enabled,
                    flagCountry = com.foxhole.vpn.vpnLanguageFlagCountry(value)) {
                    onSettings(settings.copy(language = value))
                    onBack()
                }
            }
            VpnSettingsDetail.BACKGROUND_UPDATES -> {
                BackgroundUpdateRow(R.string.vpn_auto_refresh_subscriptions,
                    stringResource(R.string.vpn_auto_refresh_subscriptions_help, settings.subscriptionRefreshIntervalHours),
                    settings.autoRefreshSubscriptions, enabled, "auto-refresh-subscriptions") {
                    onSettings(settings.copy(autoRefreshSubscriptions = it))
                }
                BackgroundUpdateRow(R.string.vpn_auto_check_connection_data,
                    stringResource(R.string.vpn_auto_check_connection_data_help, settings.connectionDataCheckIntervalHours),
                    settings.autoCheckConnectionData, enabled, "auto-check-connection-data") {
                    onSettings(settings.copy(autoCheckConnectionData = it))
                }
            }
            VpnSettingsDetail.HELP -> HelpContent()
            VpnSettingsDetail.ABOUT -> VpnAboutContent()
        }
    }
}

@Composable
private fun FontStyleDropdown(style: VpnFontStyle, enabled: Boolean, onSelect: (VpnFontStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("font-style-anchor")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(fontStyleLabel(style)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.lin_chevron_down), null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 240.dp).testTag("font-style-menu")) {
            VpnFontStyle.entries.forEach { value ->
                DropdownMenuItem(text = { Text(stringResource(fontStyleLabel(value)), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(value) },
                    trailingIcon = { RadioButton(value == style, onClick = null) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
    }
}

internal fun fontStyleLabel(style: VpnFontStyle): Int = when (style) {
    VpnFontStyle.PIXEL -> R.string.vpn_font_pixel
    VpnFontStyle.STANDARD -> R.string.vpn_font_standard
}

@Composable
private fun HelpContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            R.string.vpn_help_add_title to R.string.vpn_help_add_body,
            R.string.vpn_help_connect_title to R.string.vpn_help_connect_body,
            R.string.vpn_help_profiles_title to R.string.vpn_help_profiles_body,
            R.string.vpn_help_protocols_title to R.string.vpn_help_protocols_body,
            R.string.vpn_router to R.string.router_control_unpair_help,
            R.string.vpn_help_updates_title to R.string.vpn_help_updates_body,
            R.string.vpn_help_privacy_title to R.string.vpn_help_privacy_body,
        ).forEach { (title, body) ->
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionLabel(label: Int, first: Boolean = false) {
    Text(stringResource(label), Modifier.padding(top = if (first) 0.dp else 24.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = LocalVpnColors.current.muted)
}

@Composable
internal fun SettingsRow(label: Int, value: String? = null, enabled: Boolean = true,
    valueFlagCountry: String? = null, onClick: () -> Unit) {
    val colors = LocalVpnColors.current
    val contentAlpha = if (enabled) 1f else .38f
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val icon = when (label) {
            R.string.vpn_profiles -> R.drawable.lin_profiles
            R.string.vpn_system_settings -> R.drawable.lin_settings
            R.string.vpn_theme -> R.drawable.lin_theme
            R.string.vpn_language -> R.drawable.lin_language
            R.string.vpn_background_updates -> R.drawable.lin_refresh
            R.string.vpn_help -> R.drawable.lin_help
            R.string.vpn_import_from_clipboard -> R.drawable.lin_clipboard
            R.string.vpn_import_file -> R.drawable.lin_file
            R.string.vpn_import_qr -> R.drawable.lin_qr
            R.string.vpn_import_manual -> R.drawable.lin_edit
            else -> R.drawable.lin_info
        }
        Icon(painterResource(icon), null, Modifier.padding(end = 14.dp).size(24.dp), tint = colors.muted.copy(alpha = contentAlpha))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = colors.foreground.copy(alpha = contentAlpha))
            if (value != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (valueFlagCountry != null) VpnFlagIcon(valueFlagCountry, MaterialTheme.typography.bodySmall)
                    Text(value, style = MaterialTheme.typography.bodySmall, color = LocalVpnColors.current.muted)
                }
            }
        }
        Icon(painterResource(R.drawable.lin_chevron_right), null, Modifier.size(24.dp), tint = colors.muted.copy(alpha = contentAlpha))
    }
    HorizontalDivider(color = LocalVpnColors.current.border.copy(alpha = .5f))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, flagCountry: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (flagCountry != null) VpnFlagIcon(flagCountry, MaterialTheme.typography.bodyMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun themeLabel(value: VpnTheme): Int = when (value) {
    VpnTheme.SYSTEM -> R.string.vpn_theme_system
    VpnTheme.DARK -> R.string.vpn_theme_foxhole
    VpnTheme.LIGHT -> R.string.vpn_theme_light
    VpnTheme.NIGHT -> R.string.vpn_theme_night
}

private fun languageLabel(value: VpnLanguage): Int = when (value) {
    VpnLanguage.SYSTEM -> R.string.vpn_language_system
    VpnLanguage.EN -> R.string.vpn_language_english
    VpnLanguage.RU -> R.string.vpn_language_russian
    VpnLanguage.JA -> R.string.vpn_language_japanese
    VpnLanguage.DE -> R.string.vpn_language_german
    VpnLanguage.FR -> R.string.vpn_language_french
    VpnLanguage.ES -> R.string.vpn_language_spanish
    VpnLanguage.ZH -> R.string.vpn_language_chinese
}

@Composable
private fun BackgroundUpdateRow(label: Int, help: String, checked: Boolean, enabled: Boolean,
    tag: String, onChange: (Boolean) -> Unit) {
    val title = stringResource(label)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(help, style = MaterialTheme.typography.bodySmall, color = LocalVpnColors.current.muted)
        }
        VpnSwitch(checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = title })
    }
}
