package com.foxhole.vpn.appupdates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.foxhole.vpn.MainActivity
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.withVpnLocale

internal class VpnAppUpdateNotifier(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("vpn-app-updates", Context.MODE_PRIVATE)

    fun notify(release: VpnAppRelease): Boolean {
        val localized = context.withVpnLocale((context as? VpnApplication)?.controller?.settings?.value?.language ?: VpnLanguage.SYSTEM)
        val code = release.manifest.versionCode
        if (code <= preferences.getLong("last-notified-code", 0) ||
            code <= VpnAppApkVerifier(context).installedCode() ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(R.string.vpn_app_update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 490, Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_APP_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_vpn)
            .setContentTitle(localized.getString(R.string.vpn_app_update_notification_title))
            .setContentText(localized.getString(R.string.vpn_app_update_notification_body, release.manifest.versionName))
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            preferences.edit { putLong("last-notified-code", code) }
            true
        } catch (_: SecurityException) { false }
    }

    fun clear() {
        preferences.edit { remove("last-notified-code") }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_OPEN_APP_UPDATE = "com.foxhole.vpn.action.OPEN_APP_UPDATE"
        private const val CHANNEL = "foxhole_vpn_app_updates"
        private const val NOTIFICATION_ID = 9600
    }
}
