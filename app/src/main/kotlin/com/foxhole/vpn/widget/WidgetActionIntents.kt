package com.foxhole.vpn.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.foxhole.vpn.MainActivity
import com.foxhole.vpn.VpnApplication

internal data class WidgetCommand(val action: WidgetAction, val profileId: String?)

internal fun widgetCommandIntent(
    context: Context,
    widgetId: Int,
    action: WidgetAction,
    profileId: String?,
): Intent = widgetIntent(context, WidgetActionReceiver::class.java, widgetId, action, profileId)

internal fun widgetPermissionIntent(
    context: Context,
    widgetId: Int,
    action: WidgetAction,
    profileId: String?,
): Intent = widgetIntent(context, WidgetActionActivity::class.java, widgetId, action, profileId)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun widgetIntent(
    context: Context,
    target: Class<*>,
    widgetId: Int,
    action: WidgetAction,
    profileId: String?,
): Intent = Intent(context, target)
    .setAction(WIDGET_ACTION_PREFIX + action.name)
    .setData(
        Uri.Builder().scheme("foxhole-vpn-widget").authority(widgetId.toString())
            .appendPath(action.name).appendQueryParameter("profile", profileId).build(),
    )
    .putExtra(WIDGET_PROFILE_ID, profileId)

internal fun widgetCommandPendingIntent(
    context: Context,
    widgetId: Int,
    action: WidgetAction,
    profileId: String?,
    permissionRequired: Boolean = false,
): PendingIntent {
    if (action == WidgetAction.START &&
        (context.applicationContext as VpnApplication).licenseConsent.state.value.accepted.not()) {
        return widgetOpenAppPendingIntent(context, widgetId)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return if (action == WidgetAction.START && permissionRequired) {
        PendingIntent.getActivity(context, widgetId, widgetPermissionIntent(context, widgetId, action, profileId), flags)
    } else {
        PendingIntent.getBroadcast(context, widgetId, widgetCommandIntent(context, widgetId, action, profileId), flags)
    }
}

internal fun widgetOpenAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun widgetOpenAppPendingIntent(context: Context, widgetId: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        widgetId,
        widgetOpenAppIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun widgetCommand(context: Context, intent: Intent?): WidgetCommand? {
    return widgetCommandFor(context, intent, WidgetActionReceiver::class.java)
}

internal fun widgetPermissionCommand(context: Context, intent: Intent?): WidgetCommand? {
    return widgetCommandFor(context, intent, WidgetActionActivity::class.java)
}

private fun widgetCommandFor(context: Context, intent: Intent?, target: Class<*>): WidgetCommand? {
    if (intent == null || intent.component != ComponentName(context, target)) return null
    val action = WidgetAction.entries.firstOrNull { intent.action == WIDGET_ACTION_PREFIX + it.name } ?: return null
    return try {
        WidgetCommand(action, intent.getStringExtra(WIDGET_PROFILE_ID))
    } catch (_: RuntimeException) {
        null
    }
}

private const val WIDGET_ACTION_PREFIX = "com.foxhole.vpn.widget."
private const val WIDGET_PROFILE_ID = "widget_profile_id"
