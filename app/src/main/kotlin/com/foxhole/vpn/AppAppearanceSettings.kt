package com.foxhole.vpn

// Merge only this action's changed fields so queued saves preserve newer preferences.
internal fun mergeVpnAppearanceSettings(
    current: VpnSettings,
    previous: VpnSettings,
    updated: VpnSettings,
): VpnSettings = current.copy(
    theme = if (updated.theme != previous.theme) updated.theme else current.theme,
    language = if (updated.language != previous.language) updated.language else current.language,
    fontStyle = if (updated.fontStyle != previous.fontStyle) updated.fontStyle else current.fontStyle,
    hideRouterManagement = if (updated.hideRouterManagement != previous.hideRouterManagement)
        updated.hideRouterManagement else current.hideRouterManagement,
)
