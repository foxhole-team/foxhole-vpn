package com.foxhole.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
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

internal enum class VpnSettingsDetail { APPEARANCE, BACKGROUND_UPDATES, HELP, ABOUT }

@Composable
internal fun VpnSettingsScreen(
    settings: VpnSettings,
    onSettingsDetail: (VpnSettingsDetail) -> Unit,
    onProfiles: () -> Unit,
    onSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onAppUpdate: () -> Unit = {},
    onAppJournal: () -> Unit = {},
) {
    VpnSheetContent(R.string.vpn_settings, R.drawable.lin_settings, modifier.verticalScroll(rememberScrollState())) {
        SettingsRow(R.string.vpn_profiles, onClick = onProfiles)
        SettingsRow(R.string.vpn_system_settings, onClick = onSystemSettings)
        SettingsRow(R.string.vpn_appearance) { onSettingsDetail(VpnSettingsDetail.APPEARANCE) }
        SettingsRow(R.string.vpn_background_updates) { onSettingsDetail(VpnSettingsDetail.BACKGROUND_UPDATES) }
        SettingsRow(R.string.vpn_app_update_title, onClick = onAppUpdate)
        SettingsRow(R.string.vpn_app_journal_title, onClick = onAppJournal)
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
        VpnSettingsDetail.APPEARANCE -> R.string.vpn_appearance
        VpnSettingsDetail.BACKGROUND_UPDATES -> R.string.vpn_background_updates
        VpnSettingsDetail.HELP -> R.string.vpn_help
        VpnSettingsDetail.ABOUT -> R.string.vpn_about
    }
    val icon = when (detail) {
        VpnSettingsDetail.APPEARANCE -> R.drawable.lin_theme
        VpnSettingsDetail.BACKGROUND_UPDATES -> R.drawable.lin_refresh
        VpnSettingsDetail.HELP -> R.drawable.lin_help
        VpnSettingsDetail.ABOUT -> R.drawable.lin_info
    }
    if (detail == VpnSettingsDetail.HELP) {
        VpnRouterSheetContent(title, icon, modifier, busy = busy, scrollable = false, naturalHeight = true) {
            HelpContent()
        }
        return
    }
    VpnSheetContent(title, icon, modifier.verticalScroll(rememberScrollState()), busy = busy) {
        when (detail) {
            VpnSettingsDetail.APPEARANCE -> {
                val locales = LocalConfiguration.current.locales
                val effectiveLanguageTag = if (locales.isEmpty) "" else locales[0].toLanguageTag()
                AppearanceSettingRow(R.string.vpn_language) {
                    VpnChoiceDropdown(stringResource(languageLabel(settings.language)), enabled,
                        leadingFlagCountry = displayedLanguageFlagCountry(settings.language, effectiveLanguageTag)) { dismiss ->
                        com.foxhole.vpn.VPN_APP_LANGUAGE_CHOICES.forEach { value ->
                            DropdownMenuItem(text = { Text(stringResource(languageLabel(value)), maxLines = 1,
                                overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { VpnFlagIcon(displayedLanguageFlagCountry(value, effectiveLanguageTag), showUnknown = true) },
                                trailingIcon = { RadioButton(value == settings.language, onClick = null) },
                                onClick = { dismiss(); onSettings(settings.copy(language = value)) })
                        }
                    }
                }
                AppearanceSettingRow(R.string.vpn_font_style) {
                    FontStyleDropdown(settings.fontStyle, enabled) { onSettings(settings.copy(fontStyle = it)) }
                }
                AppearanceSettingRow(R.string.vpn_theme) {
                    VpnChoiceDropdown(stringResource(themeLabel(settings.theme)), enabled) { dismiss ->
                        VpnTheme.entries.forEach { value ->
                            DropdownMenuItem(text = { Text(stringResource(themeLabel(value)), maxLines = 1,
                                overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { RadioButton(value == settings.theme, onClick = null) },
                                onClick = { dismiss(); onSettings(settings.copy(theme = value)) })
                        }
                    }
                }
                AppearanceSettingRow(R.string.vpn_hide_router_management) {
                    VpnSwitch(settings.hideRouterManagement,
                        onCheckedChange = { onSettings(settings.copy(hideRouterManagement = it)) },
                        enabled = enabled, modifier = Modifier.testTag("hide-router-management"))
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
            VpnSettingsDetail.HELP -> Unit
            VpnSettingsDetail.ABOUT -> VpnAboutContent()
        }
    }
}

@Composable
private fun FontStyleDropdown(style: VpnFontStyle, enabled: Boolean, onSelect: (VpnFontStyle) -> Unit) {
    VpnChoiceDropdown(stringResource(fontStyleLabel(style)), enabled,
        labelStyle = vpnFontPreviewStyle(style), tag = "font-style",
        menuMaxHeight = 240.dp) { dismiss ->
        VpnFontStyle.entries.forEach { value ->
            DropdownMenuItem(text = { Text(stringResource(fontStyleLabel(value)), style = vpnFontPreviewStyle(value),
                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { dismiss(); onSelect(value) },
                trailingIcon = { RadioButton(value == style, onClick = null) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

internal fun fontStyleLabel(style: VpnFontStyle): Int = when (style) {
    VpnFontStyle.PIXEL -> R.string.vpn_font_pixel
    VpnFontStyle.STANDARD -> R.string.vpn_font_standard
}

@Composable
private fun HelpContent() {
    VpnHelpContent()
}

@Composable
private fun AppearanceSettingRow(label: Int, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * .6f)
            .animateContentSize(tween(120), alignment = Alignment.CenterEnd)) { value() }
    }
    HorizontalDivider(color = LocalVpnColors.current.border.copy(alpha = .5f))
}

@Composable
internal fun SettingsRow(label: Int, value: String? = null, enabled: Boolean = true,
    valueFlagCountry: String? = null, onClick: () -> Unit) {
    val colors = LocalVpnColors.current
    val contentAlpha = if (enabled) 1f else .38f
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val icon = when (label) {
            R.string.vpn_profiles -> R.drawable.lin_profiles
            R.string.vpn_system_settings -> R.drawable.lin_settings
            R.string.vpn_theme, R.string.vpn_appearance -> R.drawable.lin_theme
            R.string.vpn_language -> R.drawable.lin_language
            R.string.vpn_background_updates -> R.drawable.lin_refresh
            R.string.vpn_help -> R.drawable.lin_help
            R.string.vpn_app_journal_title -> R.drawable.lin_file
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
    VpnLanguage.AR -> R.string.vpn_language_arabic
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
    HorizontalDivider(color = LocalVpnColors.current.border.copy(alpha = .5f))
}
