package com.foxhole.vpn.appupdates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foxhole.vpn.VpnApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VpnPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? VpnApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(5_000) { app.resumeConnectionAfterUpdate() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The foreground connection action remains available when recovery cannot start.
            } finally {
                pending.finish()
            }
        }
    }
}
