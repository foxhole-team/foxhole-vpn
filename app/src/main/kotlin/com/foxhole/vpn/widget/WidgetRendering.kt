package com.foxhole.vpn.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.net.VpnService
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState

internal fun widgetLocalizedContext(context: Context, language: VpnLanguage): Context {
    val locales = when (language) {
        VpnLanguage.SYSTEM -> Resources.getSystem().configuration.locales
        VpnLanguage.EN -> LocaleList.forLanguageTags("en")
        VpnLanguage.RU -> LocaleList.forLanguageTags("ru")
        VpnLanguage.JA -> LocaleList.forLanguageTags("ja")
        VpnLanguage.DE -> LocaleList.forLanguageTags("de")
        VpnLanguage.FR -> LocaleList.forLanguageTags("fr")
        VpnLanguage.ES -> LocaleList.forLanguageTags("es")
        VpnLanguage.ZH -> LocaleList.forLanguageTags("zh-CN")
    }
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(locales) })
}

internal fun renderStatusWidgets(
    context: Context,
    state: VpnUiState,
    settings: VpnSettings,
    ids: IntArray? = null,
) {
    val manager = AppWidgetManager.getInstance(context)
    val widgetIds = ids ?: manager.getAppWidgetIds(ComponentName(context, StatusWidgetReceiver::class.java))
    if (widgetIds.isEmpty()) return
    widgetIds.forEach { id ->
        val options = manager.getAppWidgetOptions(id)
        val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160) < 180
        val portrait = statusWidgetViews(context, state, settings, id, compact,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240))
        val landscape = statusWidgetViews(context, state, settings, id, compact,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 240))
        manager.updateAppWidget(id, RemoteViews(landscape, portrait))
    }
}

internal fun statusWidgetViews(
    context: Context,
    state: VpnUiState,
    settings: VpnSettings,
    id: Int,
    compact: Boolean,
    widthDp: Int = 240,
): RemoteViews {
    val localized = widgetLocalizedContext(context, settings.language)
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val dark = widgetUsesDarkTheme(settings.theme, systemDark)
    val palette = widgetPalette(dark, settings.theme == VpnTheme.NIGHT)
    val label = localized.getString(widgetStatusLabel(state))
    val location = widgetLocation(state)
    val detail = widgetLocationLabel(location) ?: localized.getString(widgetLocationPlaceholder(state))
    val flag = location?.countryCode?.let { widgetFlagBitmap(context, it, compact) }
    val action = widgetAction(state)
    val permissionRequired = action == WidgetAction.START && VpnService.prepare(context) != null
    val statusColor = if (!widgetShowsRuntimeStatus(state)) palette.secondary else when (state.phase) {
        VpnPhase.CONNECTED -> palette.positive
        VpnPhase.ERROR -> palette.error
        VpnPhase.CONNECTING, VpnPhase.VALIDATING -> palette.progress
        VpnPhase.IDLE, VpnPhase.STOPPING -> palette.secondary
    }
    val actionPalette = widgetPalette(dark).let {
        if (dark) it else it.copy(positive = 0xFF27723B.toInt(), error = 0xFFB83224.toInt())
    }
    val powerColor = when (action) {
        WidgetAction.START -> actionPalette.positive
        WidgetAction.STOP -> actionPalette.error
        null -> palette.secondary
    }
    val layout = if (compact) R.layout.widget_status_compact else R.layout.widget_status
    return RemoteViews(context.packageName, layout).apply {
        val (leftInset, rightInset) = widgetStatusActionInsets(widthDp, context.resources.displayMetrics.density)
        setViewPadding(R.id.widget_status_action_row, leftInset, 0, rightInset, 0)
        setImageViewResource(
            R.id.widget_status_background,
            if (settings.theme == VpnTheme.NIGHT) R.drawable.widget_status_background_night
            else if (dark) R.drawable.widget_status_background_dark else R.drawable.widget_status_background_light,
        )
        setTextViewText(R.id.widget_status_label, label)
        setTextViewText(R.id.widget_status_detail, detail)
        setTextColor(R.id.widget_status_label, statusColor)
        setTextColor(R.id.widget_status_detail, palette.text)
        setImageViewBitmap(R.id.widget_status_flag, flag)
        setViewVisibility(R.id.widget_status_flag, if (flag == null) View.GONE else View.VISIBLE)
        setInt(R.id.widget_status_device, "setColorFilter", statusColor)
        setInt(R.id.widget_status_power_surface, "setColorFilter", if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        setInt(R.id.widget_status_power_ring, "setColorFilter", powerColor)
        setInt(R.id.widget_status_power_icon, "setColorFilter", powerColor)
        setTextViewText(R.id.widget_status_power_label, localized.getString(widgetActionLabel(state)).uppercase())
        setTextColor(R.id.widget_status_power_label, powerColor)
        setOnClickPendingIntent(R.id.widget_status_root, null)
        setContentDescription(R.id.widget_status_root, null)
        setOnClickPendingIntent(R.id.widget_status_logo_touch, widgetOpenAppPendingIntent(context, id))
        setContentDescription(R.id.widget_status_logo_touch, localized.getString(R.string.widget_open_app))
        setContentDescription(R.id.widget_status_power_touch, localized.getString(widgetActionLabel(state)))
        setOnClickPendingIntent(
            R.id.widget_status_power_touch,
            action?.let { widgetCommandPendingIntent(context, id, it, state.selectedProfileId, permissionRequired) },
        )
        setBoolean(R.id.widget_status_power_touch, "setEnabled", action != null)
    }
}

internal fun renderFoxWidgets(
    context: Context,
    state: VpnUiState,
    settings: VpnSettings,
    ids: IntArray? = null,
) {
    val manager = AppWidgetManager.getInstance(context)
    val widgetIds = ids ?: manager.getAppWidgetIds(ComponentName(context, FoxStatusWidgetReceiver::class.java))
    if (widgetIds.isEmpty()) return
    val localized = widgetLocalizedContext(context, settings.language)
    val connected = state.isReady && state.isConnected
    val animate = connected && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    val layout = if (animate) R.layout.widget_fox_connected else R.layout.widget_fox
    val action = widgetAction(state)
    val permissionRequired = action == WidgetAction.START && VpnService.prepare(context) != null
    val staticFrame = widgetFoxFrameIndex(if (connected) state.phase else VpnPhase.IDLE)
    widgetIds.forEach { id ->
        val views = RemoteViews(context.packageName, layout).apply {
            if (!animate) setImageViewResource(
                R.id.widget_fox_image,
                if (staticFrame == 0) R.drawable.fhg_status_frame_1 else R.drawable.fhg_status_frame_4,
            )
            setContentDescription(
                R.id.widget_fox_root,
                "${localized.getString(widgetStatusLabel(state))}. ${localized.getString(widgetActionLabel(state))}",
            )
            setOnClickPendingIntent(
                R.id.widget_fox_root,
                action?.let { widgetCommandPendingIntent(context, id, it, state.selectedProfileId, permissionRequired) },
            )
            setBoolean(R.id.widget_fox_root, "setEnabled", action != null)
        }
        manager.updateAppWidget(id, views)
    }
}

private fun widgetActionLabel(state: VpnUiState): Int = when (widgetAction(state)) {
    WidgetAction.START -> R.string.widget_connect
    WidgetAction.STOP -> if (state.phase == VpnPhase.CONNECTING || state.phase == VpnPhase.VALIDATING) {
        R.string.widget_cancel
    } else {
        R.string.widget_disconnect
    }
    null -> when {
        !widgetShowsRuntimeStatus(state) -> R.string.widget_loading
        state.phase == VpnPhase.STOPPING -> R.string.widget_stopping
        state.selectedProfile == null -> R.string.widget_choose_profile
        else -> widgetStatusLabel(state)
    }
}

private data class WidgetPalette(val text: Int, val secondary: Int, val positive: Int, val error: Int, val progress: Int)

private fun widgetPalette(dark: Boolean, monochrome: Boolean = false): WidgetPalette = if (monochrome) {
    WidgetPalette(0xFFFFFFFF.toInt(), 0xFF777777.toInt(), 0xFFFFFFFF.toInt(), 0xFFB5B5B5.toInt(), 0xFFB5B5B5.toInt())
} else if (dark) {
    WidgetPalette(0xFFF0E6D6.toInt(), 0xFFB49A76.toInt(), 0xFF7FB34A.toInt(), 0xFFE0562A.toInt(), 0xFF68B7DC.toInt())
} else {
    WidgetPalette(0xFF292620.toInt(), 0xFF695D4A.toInt(), 0xFF376615.toInt(), 0xFFB53116.toInt(), 0xFF23688D.toInt())
}
