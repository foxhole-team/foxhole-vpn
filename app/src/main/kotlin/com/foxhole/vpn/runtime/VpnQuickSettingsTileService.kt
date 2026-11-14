package com.foxhole.vpn.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.widget.WidgetAction
import com.foxhole.vpn.widget.widgetOpenAppIntent
import com.foxhole.vpn.widget.widgetPermissionIntent
import com.foxhole.vpn.withVpnLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

class VpnQuickSettingsTileService : TileService() {
    private val owner get() = application as VpnApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var clicking: Job? = null
    private val unlockGate = VpnTileUnlockGate()

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        unlockGate.newListeningSession()
        listening = scope.launch {
            combine(owner.controller.state, owner.controller.settings, owner.licenseConsent.state) { state, settings, license ->
                Triple(state, settings, license)
            }.collect { (state, settings, _) -> refresh(state, settings) }
        }
    }

    override fun onStopListening() {
        listening?.cancel(); listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        unlockGate.newListeningSession()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (clicking?.isActive == true || unlockGate.pending) return
        clicking = scope.launch {
            // A delayed vault read must not turn into an unbounded background start.
            if (withTimeoutOrNull(2_000L) { owner.controller.awaitReady(); true } != true) return@launch
            when (vpnTileClick(owner.controller.state.value, owner.licenseConsent.state.value.loaded,
                owner.licenseConsent.state.value.accepted)) {
                VpnTileClick.STOP -> owner.controller.disconnect()
                VpnTileClick.NONE -> Unit
                else -> if (isLocked) {
                    val ticket = unlockGate.claim() ?: return@launch
                    unlockAndRun {
                        if (scope.isActive && unlockGate.consume(ticket)) openStartFlow()
                    }
                } else openStartFlow()
            }
        }
    }

    private fun openStartFlow() {
        val current = owner.controller.state.value
        val license = owner.licenseConsent.state.value
        val intent = when (vpnTileClick(current, license.loaded, license.accepted)) {
            VpnTileClick.START_ACTIVITY -> widgetPermissionIntent(this, TILE_REQUEST, WidgetAction.START, current.selectedProfileId)
            VpnTileClick.OPEN_APP -> widgetOpenAppIntent(this)
            else -> return
        }
        // This user-visible activity owns both license and Android VPN consent checks.
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, TILE_REQUEST,
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else collapseOnLegacyAndroid(intent)
        } catch (_: IllegalStateException) { requestVpnTileRefresh(this) }
        catch (_: SecurityException) { requestVpnTileRefresh(this) }
    }

    // API 24-33 has only the Intent overload; API 34+ uses PendingIntent above.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun collapseOnLegacyAndroid(intent: Intent) {
        check(Build.VERSION.SDK_INT < 34)
        startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refresh(state: VpnUiState, settings: VpnSettings) {
        val tile = qsTile ?: return
        val localized = withVpnLocale(settings.language)
        val presentation = vpnSystemPresentation(state)
        val label = localized.getString(R.string.vpn_notification_title)
        val status = localized.getString(presentation.statusRes)
        tile.label = label
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = status
        tile.contentDescription = "$label, $status"
        tile.state = when {
            presentation.action == VpnSystemAction.NONE -> Tile.STATE_UNAVAILABLE
            presentation.active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private companion object { const val TILE_REQUEST = 1017 }
}

internal fun requestVpnTileRefresh(context: Context) {
    try { TileService.requestListeningState(context, ComponentName(context, VpnQuickSettingsTileService::class.java)) }
    catch (_: IllegalArgumentException) { /* Tile has not been added. */ }
    catch (_: SecurityException) { /* System owns tile availability. */ }
}

internal fun startVpnQuickSettingsTileUpdates(context: Context, scope: CoroutineScope) {
    val app = context.applicationContext as VpnApplication
    scope.launch {
        combine(app.controller.state, app.controller.settings, app.licenseConsent.state) { state, settings, license ->
            Triple(vpnSystemPresentation(state), settings.language, license.loaded to license.accepted)
        }.distinctUntilChanged().collect { requestVpnTileRefresh(context) }
    }
}
