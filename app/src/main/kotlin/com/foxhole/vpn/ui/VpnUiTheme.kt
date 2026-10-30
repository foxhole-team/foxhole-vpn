package com.foxhole.vpn.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnFontStyle

@Immutable
internal data class VpnColors(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val faint: Color,
    val panel: Color,
    val border: Color,
    val connected: Color,
    val light: Boolean,
    val monochrome: Boolean = false,
    val globeLandTint: Color? = null,
    val globeDeviceTint: Color? = null,
    val globeServerTint: Color? = null,
)

private val DarkColors = VpnColors(
    Color(0xFF0B0A08), Color(0xFFF0E6D6), Color(0xFFB49A76), Color(0xFF8B7A5E),
    Color(0xFF15120D), Color(0xFF4A3C29), Color(0xFF7FB34A), false,
)
private val LightColors = VpnColors(
    Color(0xFFF2E8D5), Color(0xFF2A2118), Color(0xFF665440), Color(0xFF756149),
    Color(0xFFFFF8EA), Color(0xFFB7A17D), Color(0xFF27723B), true,
)
private val NightColors = VpnColors(
    Color.Black, Color.White, Color(0xFF777777), Color(0xFF777777),
    Color(0xFF0A0A0A), Color(0xFF242424), Color.White, false, monochrome = true,
)
internal val LocalVpnColors = staticCompositionLocalOf { DarkColors }
private val Mono = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))
private val Pixel = FontFamily(Font(R.font.tiny5_regular))
private val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.18.sp, lineHeight = 19.228.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.156.sp, lineHeight = 16.192.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp),
    titleLarge = TextStyle(fontFamily = Pixel, fontSize = 23.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = Pixel, fontSize = 18.912.sp, lineHeight = 23.276.sp),
    titleSmall = TextStyle(fontFamily = Pixel, fontSize = 16.55.sp, lineHeight = 23.276.sp),
    labelLarge = TextStyle(fontFamily = Pixel, fontSize = 17.73.sp, lineHeight = 20.24.sp),
)

@Composable
internal fun VpnUiTheme(theme: VpnTheme, fontStyle: VpnFontStyle = VpnFontStyle.PIXEL, content: @Composable () -> Unit) {
    val base = vpnColorsFor(theme, isSystemInDarkTheme())
    val light = base.light
    val scheme = if (theme == VpnTheme.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (light) dynamicLightColorScheme(LocalContext.current) else dynamicDarkColorScheme(LocalContext.current)
    } else vpnMaterialSchemeFor(base)
    val colors = base.copy(
        background = scheme.background,
        foreground = scheme.onBackground,
        panel = scheme.surface,
        muted = scheme.onSurfaceVariant,
        border = scheme.outline,
        globeLandTint = if (theme == VpnTheme.DARK || theme == VpnTheme.SYSTEM) {
            lerp(scheme.onSurfaceVariant, scheme.onBackground, if (light) .8f else .42f)
        } else null,
        globeDeviceTint = vpnGlobeDeviceTint(theme),
        globeServerTint = vpnGlobeServerTint(theme),
    )
    CompositionLocalProvider(LocalVpnColors provides colors) {
        val typography = remember(fontStyle) { vpnTypographyFor(fontStyle) }
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

internal fun vpnTypographyFor(style: VpnFontStyle): Typography = if (style == VpnFontStyle.PIXEL) AppTypography else AppTypography.copy(
    titleLarge = AppTypography.titleLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    titleMedium = AppTypography.titleMedium.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    titleSmall = AppTypography.titleSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
    labelLarge = AppTypography.labelLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
)

internal fun vpnGlobeDeviceTint(theme: VpnTheme): Color? = when (theme) {
    VpnTheme.DARK -> Color(0xFF6E94AF)
    VpnTheme.LIGHT -> Color(0xFFE8EEF5)
    VpnTheme.SYSTEM -> Color(0xFFD0D0D0)
    else -> null
}

internal fun vpnGlobeServerTint(theme: VpnTheme): Color? = when (theme) {
    VpnTheme.DARK -> Color(0xFF7FB34A)
    VpnTheme.LIGHT -> Color.White
    else -> null
}

internal fun vpnColorsFor(theme: VpnTheme, systemDark: Boolean): VpnColors = when (theme) {
    VpnTheme.SYSTEM -> if (systemDark) DarkColors else LightColors
    VpnTheme.DARK -> DarkColors
    VpnTheme.LIGHT -> LightColors
    VpnTheme.NIGHT -> NightColors
}

internal fun vpnMaterialSchemeFor(base: VpnColors): ColorScheme = when {
    base.monochrome -> darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        primaryContainer = Color(0xFF1C1C1C), onPrimaryContainer = Color.White,
        inversePrimary = Color.Black,
        secondary = Color(0xFFB5B5B5), onSecondary = Color.Black,
        secondaryContainer = Color(0xFF1C1C1C), onSecondaryContainer = Color.White,
        tertiary = Color(0xFFB5B5B5), onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF1C1C1C), onTertiaryContainer = Color.White,
        background = base.background, onBackground = base.foreground,
        surface = base.panel, onSurface = base.foreground,
        surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = base.muted,
        surfaceTint = Color.White,
        inverseSurface = Color.White, inverseOnSurface = Color.Black,
        error = Color.White, onError = Color.Black,
        errorContainer = Color(0xFF1C1C1C), onErrorContainer = Color.White,
        outline = base.border, outlineVariant = base.border, scrim = Color.Black,
        surfaceBright = Color(0xFF1C1C1C), surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = base.panel,
        surfaceContainer = base.panel, surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF242424),
    )
    base.light -> lightColorScheme(
        primary = base.connected, background = base.background, onBackground = base.foreground,
        surface = base.panel, onSurface = base.foreground, onSurfaceVariant = base.muted,
        outline = base.border, error = Color(0xFFB83224),
    )
    else -> darkColorScheme(
        primary = base.connected, background = base.background, onBackground = base.foreground,
        surface = base.panel, onSurface = base.foreground, onSurfaceVariant = base.muted,
        outline = base.border, error = Color(0xFFE0562A),
    )
}
