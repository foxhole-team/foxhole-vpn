package com.foxhole.vpn.router

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.foxhole.vpn.R
import com.foxhole.vpn.runtime.vpnServiceRuntime
import com.foxhole.vpn.withVpnLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class RouterHomeAutomationService : Service() {
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binding by lazy { vpnServiceRuntime(application) }
    private val home get() = binding.home
    private val controller get() = binding.controller
    private var attachedEpoch: Long? = null
    private var foreground = false
    private var observing = false
    // Epochs may repeat in a later process; notification authority cannot.
    private val notificationLifetime = UUID.randomUUID().toString()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val epoch = intent?.getLongExtra(EXTRA_REQUEST_EPOCH, Long.MIN_VALUE) ?: Long.MIN_VALUE
        val data = intent?.data
        val ownedIdentity = intent?.action != ACTION_STOP || data == null || data == stopIdentity(epoch)
        val command = routerHomeServiceCommand(intent?.action, ownedIdentity && home.isCurrentRequest(epoch), home.canForegroundAttach(epoch))
        when (command) {
            RouterHomeServiceCommand.START -> startOwned(epoch, startId)
            RouterHomeServiceCommand.STOP -> {
                if (home.status.value != RouterHomeMonitorState.OFF) home.disableFromService(epoch)
                if (home.status.value == RouterHomeMonitorState.OFF || attachedEpoch == null) finishOwned(startId)
            }
            RouterHomeServiceCommand.IGNORE -> if (attachedEpoch == null) finishOwned(startId)
        }
        return START_NOT_STICKY
    }

    private fun startOwned(epoch: Long, startId: Int) {
        if (!notificationAvailable() || !routerLocalNetworkAccessGranted(this)) {
            home.disableFromService(epoch)
            finishOwned(startId)
            return
        }
        if (!showNotification(epoch, home.status.value) || !home.foregroundAttached(epoch)) {
            home.disableFromService(epoch)
            finishOwned(startId)
            return
        }
        attachedEpoch = epoch
        observeStatus()
    }

    private fun observeStatus() {
        if (observing) return
        observing = true
        notificationScope.launch {
            combine(home.status, controller.settings.map { it.language }.distinctUntilChanged()) { status, _ -> status }
                .collect { status ->
                    val epoch = attachedEpoch ?: return@collect
                    if (!home.isCurrentRequest(epoch)) { finishOwned(); return@collect }
                    when (routerHomeStatusAction(home.isCurrentRequest(epoch), status == home.status.value,
                        home.isStopping(epoch), status)) {
                        RouterHomeStatusAction.IGNORE -> return@collect
                        RouterHomeStatusAction.FINISH -> finishOwned()
                        RouterHomeStatusAction.UPDATE -> {
                            if (!notificationAvailable() || !routerLocalNetworkAccessGranted(this@RouterHomeAutomationService) ||
                                !showNotification(epoch, status)) {
                                home.disableFromService(epoch)
                                finishOwned()
                            }
                        }
                    }
                }
        }
    }

    private fun notificationAvailable(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java) ?: return false
            val localized = withVpnLocale(controller.settings.value.language)
            manager.createNotificationChannel(NotificationChannel(ROUTER_HOME_NOTIFICATION_CHANNEL,
                localized.getString(R.string.vpn_router_home_notification_channel), NotificationManager.IMPORTANCE_LOW))
            routerHomeNotificationsAvailable(this)
        } catch (_: RuntimeException) { false }
    }

    private fun showNotification(epoch: Long, status: RouterHomeMonitorState): Boolean = try {
        if (!home.canForegroundAttach(epoch) || status == RouterHomeMonitorState.OFF) false else {
            val localized = withVpnLocale(controller.settings.value.language)
            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = Intent(this, binding.activity)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val stop = Intent(this, RouterHomeAutomationService::class.java)
                .setAction(ACTION_STOP).setData(stopIdentity(epoch))
                .putExtra(EXTRA_REQUEST_EPOCH, epoch)
            val notification = NotificationCompat.Builder(this, ROUTER_HOME_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.lin_router)
                .setContentTitle(localized.getString(R.string.vpn_router_home_notification_title))
                .setContentText(localized.getString(when (status) {
                    RouterHomeMonitorState.ROUTER_UNAVAILABLE -> R.string.vpn_router_home_notification_unavailable
                    RouterHomeMonitorState.PROFILE_UNSUPPORTED -> R.string.vpn_router_home_notification_profile_unsupported
                    else -> R.string.vpn_router_home_notification_monitoring
                }))
                .setContentIntent(PendingIntent.getActivity(this, 40, open, immutable))
                .addAction(R.drawable.lin_power, localized.getString(R.string.vpn_router_home_notification_stop),
                    PendingIntent.getService(this, 41, stop, immutable))
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW).build()
            ServiceCompat.startForeground(this, NOTIFICATION, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            foreground = true
            true
        }
    } catch (_: RuntimeException) { false }

    private fun stopIdentity(epoch: Long): Uri = Uri.parse("foxhole-router-home://request/$notificationLifetime/$epoch/stop")

    private fun finishOwned(startId: Int? = null) {
        if (startId != null && !stopSelfResult(startId)) return
        if (startId == null) stopSelf()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    override fun onDestroy() {
        notificationScope.cancel()
        attachedEpoch?.let(home::foregroundStopped)
        attachedEpoch = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "com.foxhole.vpn.action.ROUTER_HOME_START"
        internal const val ACTION_STOP = "com.foxhole.vpn.action.ROUTER_HOME_STOP"
        internal const val EXTRA_REQUEST_EPOCH = "router_home_request_epoch"
        private const val NOTIFICATION = 1002
    }
}

internal enum class RouterHomeServiceCommand { START, STOP, IGNORE }
internal enum class RouterHomeStatusAction { IGNORE, FINISH, UPDATE }
internal const val ROUTER_HOME_NOTIFICATION_CHANNEL = "router_home_monitor"

internal fun routerHomeStatusAction(
    ownedRequest: Boolean,
    currentEmission: Boolean,
    stopping: Boolean,
    status: RouterHomeMonitorState,
): RouterHomeStatusAction = when {
    !ownedRequest || !currentEmission || stopping -> RouterHomeStatusAction.IGNORE
    status == RouterHomeMonitorState.OFF -> RouterHomeStatusAction.FINISH
    else -> RouterHomeStatusAction.UPDATE
}

internal fun routerHomeServiceCommand(
    action: String?,
    ownedRequest: Boolean,
    startEligible: Boolean = ownedRequest,
): RouterHomeServiceCommand = when {
    !ownedRequest -> RouterHomeServiceCommand.IGNORE
    action == RouterHomeAutomationService.ACTION_START && startEligible -> RouterHomeServiceCommand.START
    action == RouterHomeAutomationService.ACTION_STOP -> RouterHomeServiceCommand.STOP
    else -> RouterHomeServiceCommand.IGNORE
}

internal fun routerHomeNotificationAllowed(sdk: Int, permissionGranted: Boolean, appEnabled: Boolean, channelImportance: Int?): Boolean =
    (sdk < 33 || permissionGranted) && appEnabled &&
        (channelImportance ?: NotificationManager.IMPORTANCE_LOW) >= NotificationManager.IMPORTANCE_LOW

internal fun routerHomeNotificationsAvailable(context: Context): Boolean {
    return try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        routerHomeNotificationAllowed(Build.VERSION.SDK_INT,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            manager.getNotificationChannel(ROUTER_HOME_NOTIFICATION_CHANNEL)?.importance)
    } catch (_: RuntimeException) { false }
}
