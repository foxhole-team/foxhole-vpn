package com.foxhole.vpn.ui

import androidx.compose.ui.graphics.Color
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class VpnThemePaletteTest {
    @Test fun markerOverridesKeepNightBrightenSystemAndBalanceFoxholeColors() {
        assertEquals(Color(0xFF6E94AF), vpnGlobeDeviceTint(VpnTheme.DARK))
        assertEquals(Color(0xFF7FB34A), vpnGlobeServerTint(VpnTheme.DARK))
        assertEquals(Color.White, vpnGlobeServerTint(VpnTheme.LIGHT))
        assertEquals(Color(0xFFD0D0D0), vpnGlobeDeviceTint(VpnTheme.SYSTEM))
        assertNull(vpnGlobeDeviceTint(VpnTheme.NIGHT))
        assertNull(vpnGlobeServerTint(VpnTheme.NIGHT))
        assertNull(vpnGlobeServerTint(VpnTheme.SYSTEM))
    }
    @Test fun nightUsesTheGuardMonochromePalette() {
        val colors = vpnColorsFor(VpnTheme.NIGHT, systemDark = false)
        assertEquals(Color.Black, colors.background)
        assertEquals(Color(0xFF0A0A0A), colors.panel)
        assertEquals(Color.White, colors.foreground)
        assertEquals(Color.White, colors.connected)
        assertEquals(Color(0xFF777777), colors.muted)
        assertEquals(Color(0xFF777777), colors.faint)
        assertEquals(Color(0xFF242424), colors.border)
        assertTrue(colors.monochrome)
        assertFalse(colors.light)
        assertEquals(colors, vpnColorsFor(VpnTheme.NIGHT, systemDark = true))
    }

    @Test fun foxholeRetainsItsExistingWarmPalette() {
        val colors = vpnColorsFor(VpnTheme.DARK, systemDark = false)
        assertEquals(Color(0xFF0B0A08), colors.background)
        assertEquals(Color(0xFFF0E6D6), colors.foreground)
        assertEquals(Color(0xFF15120D), colors.panel)
        assertEquals(Color(0xFFB49A76), colors.muted)
        assertEquals(Color(0xFF8B7A5E), colors.faint)
        assertEquals(Color(0xFF4A3C29), colors.border)
        assertEquals(Color(0xFF7FB34A), colors.connected)
        assertFalse(colors.monochrome)
        assertFalse(colors.light)
    }

    @Test fun systemFollowsLightOrStandardDarkInsteadOfNight() {
        assertEquals(vpnColorsFor(VpnTheme.LIGHT, false), vpnColorsFor(VpnTheme.SYSTEM, false))
        assertEquals(vpnColorsFor(VpnTheme.DARK, true), vpnColorsFor(VpnTheme.SYSTEM, true))
        assertFalse(vpnColorsFor(VpnTheme.SYSTEM, true).monochrome)
        assertTrue(vpnColorsFor(VpnTheme.SYSTEM, false).light)
    }

    @Test fun nightMaterialControlsAndContainersHaveNoColoredFallbacks() {
        val scheme = vpnMaterialSchemeFor(vpnColorsFor(VpnTheme.NIGHT, true))
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.White, scheme.primary)
        assertEquals(Color.Black, scheme.onPrimary)
        val colors = listOf(scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
            scheme.secondary, scheme.onSecondary, scheme.secondaryContainer, scheme.onSecondaryContainer,
            scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer,
            scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface,
            scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.surfaceTint,
            scheme.inverseSurface, scheme.inverseOnSurface, scheme.inversePrimary,
            scheme.error, scheme.onError, scheme.errorContainer, scheme.onErrorContainer,
            scheme.outline, scheme.outlineVariant, scheme.scrim, scheme.surfaceBright, scheme.surfaceDim,
            scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
            scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
        colors.forEach { color ->
            assertEquals(color.red, color.green, 0f)
            assertEquals(color.green, color.blue, 0f)
        }
    }

    @Test fun visibleThemeNamesKeepTheLegacyDarkStorageIdentity() {
        assertEquals(R.string.vpn_theme_foxhole, themeLabel(VpnTheme.DARK))
        assertEquals(R.string.vpn_theme_night, themeLabel(VpnTheme.NIGHT))
        assertEquals(R.string.vpn_theme_system, themeLabel(VpnTheme.SYSTEM))
        assertEquals(R.string.vpn_theme_light, themeLabel(VpnTheme.LIGHT))
    }
}
