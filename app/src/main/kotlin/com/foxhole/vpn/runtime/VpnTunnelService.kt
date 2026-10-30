package com.foxhole.vpn.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.withVpnLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VpnTunnelService : VpnService() {
    @Volatile internal var latestStartId: Int = 0
        private set
    @Volatile internal var latestCommandTicket: Long = -1
        private set
    @Volatile internal var pendingCommandTicket: Long = -1
        private set
    private val binding by lazy { vpnServiceRuntime(application) }
    private val controller get() = binding.controller
    private val connectivity by lazy { requireNotNull(getSystemService(ConnectivityManager::class.java)) }
    private var registered = false
    private var foreground = false
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var upstream: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (upstream != network) controller.underlyingChanged()
            upstream = network
        }
        override fun onLost(network: Network) {
            if (upstream == network) { upstream = null; controller.underlyingChanged() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback)
        registered = true
        notificationScope.launch { controller.state.collect { if (foreground) showForegroundNotification() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            controller.serviceStop(this, startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_CONNECT) { finishOwnedService(); return START_NOT_STICKY }
        val incomingTicket = intent.getLongExtra(EXTRA_COMMAND, -1)
        showForegroundNotification()
        controller.serviceCommand(this, intent.getStringExtra(EXTRA_PROFILE), incomingTicket, startId)
        return START_NOT_STICKY
    }

    internal fun recordPendingCommand(ticket: Long) { pendingCommandTicket = ticket }

    internal fun recordAcceptedCommand(ticket: Long) {
        latestCommandTicket = ticket
        if (pendingCommandTicket == ticket) pendingCommandTicket = -1
    }

    fun protectSocket(socket: Int): Boolean = protect(socket)

    override fun onRevoke() { controller.serviceRevoked(this); super.onRevoke() }

    override fun onDestroy() {
        if (registered) { runCatching { connectivity.unregisterNetworkCallback(callback) }; registered = false }
        controller.serviceRevoked(this)
        notificationScope.cancel()
        super.onDestroy()
    }

    internal fun finishOwnedService(expectedStartId: Int = latestStartId) {
        if (expectedStartId > 0 && stopSelfResult(expectedStartId)) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    internal fun refreshOwnedNotification() { if (foreground) showForegroundNotification() }

    private fun showForegroundNotification() {
        val localized = withVpnLocale(controller.settings.value.language)
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, binding.activity)
        val stop = Intent(this, VpnTunnelService::class.java).setAction(ACTION_STOP)
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_vpn)
            .setContentTitle(localized.getString(R.string.vpn_notification_title))
            .setContentText(localized.getString(when (controller.state.value.phase) {
                VpnPhase.IDLE -> R.string.vpn_status_idle
                VpnPhase.CONNECTING -> R.string.vpn_status_connecting
                VpnPhase.VALIDATING -> R.string.vpn_status_validating
                VpnPhase.CONNECTED -> R.string.vpn_status_connected
                VpnPhase.STOPPING -> R.string.vpn_status_stopping
                VpnPhase.ERROR -> R.string.vpn_status_error
            }))
            .setContentIntent(PendingIntent.getActivity(this, 10, open, immutable))
            .addAction(R.drawable.ic_notification_vpn, localized.getString(R.string.vpn_notification_stop), PendingIntent.getService(this, 11, stop, immutable))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        foreground = true
    }

    companion object {
        const val ACTION_CONNECT = "com.foxhole.vpn.action.CONNECT"
        const val ACTION_STOP = "com.foxhole.vpn.action.STOP"
        const val EXTRA_PROFILE = "profile_id"
        const val EXTRA_COMMAND = "command_generation"
        private const val CHANNEL = "vpn_connection"
        private const val NOTIFICATION = 1001
    }
}
