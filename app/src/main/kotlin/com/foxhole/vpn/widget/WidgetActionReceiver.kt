package com.foxhole.vpn.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.foxhole.vpn.VpnApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = widgetCommand(context, intent) ?: return
        val controller = (context.applicationContext as VpnApplication).controller
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                // A slow vault load must not defer a start beyond the user interaction.
                withTimeoutOrNull(2_000L) {
                    if (command.action == WidgetAction.START) controller.awaitReady()
                    val permissionGranted = command.action == WidgetAction.STOP || VpnService.prepare(context) == null
                    when (widgetDecision(command.action, command.profileId, controller.state.value, permissionGranted)) {
                        WidgetDecision.CONNECT -> controller.connectSelected()
                        WidgetDecision.DISCONNECT -> controller.disconnect()
                        WidgetDecision.REQUEST_PERMISSION, WidgetDecision.NONE -> Unit
                    }
                }
            } finally {
                requestWidgetRefresh()
                pending.finish()
            }
        }
    }
}
