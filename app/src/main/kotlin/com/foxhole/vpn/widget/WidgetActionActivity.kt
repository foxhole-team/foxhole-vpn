package com.foxhole.vpn.widget

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.foxhole.vpn.VpnApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WidgetActionActivity : ComponentActivity() {
    private val controller get() = (application as VpnApplication).controller
    private var requestedProfileId: String? = null
    private var permissionPending = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        permissionPending = false
        if (result.resultCode == RESULT_OK && VpnService.prepare(this) == null) {
            execute(WidgetCommand(WidgetAction.START, requestedProfileId))
        } else {
            requestWidgetRefresh()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            requestedProfileId = savedInstanceState.getString(PENDING_PROFILE)
            permissionPending = savedInstanceState.getBoolean(PERMISSION_PENDING)
            if (permissionPending) return
        }
        val command = widgetPermissionCommand(this, intent)
        if (command == null) {
            finish()
        } else {
            execute(command)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_PROFILE, requestedProfileId)
        outState.putBoolean(PERMISSION_PENDING, permissionPending)
        super.onSaveInstanceState(outState)
    }

    private fun execute(command: WidgetCommand) {
        lifecycleScope.launch {
            controller.awaitReady()
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            if (command.action == WidgetAction.START) {
                val license = (application as VpnApplication).licenseConsent.state.first { it.loaded }
                if (!license.accepted) {
                    startActivity(widgetOpenAppIntent(this@WidgetActionActivity))
                    requestWidgetRefresh()
                    finish()
                    return@launch
                }
            }
            executeReady(command)
        }
    }

    private fun executeReady(command: WidgetCommand) {
        val prepare = if (command.action == WidgetAction.START) VpnService.prepare(this) else null
        when (widgetDecision(command.action, command.profileId, controller.state.value, prepare == null)) {
            WidgetDecision.CONNECT -> {
                controller.connectSelected()
                finish()
            }
            WidgetDecision.DISCONNECT -> {
                controller.disconnect()
                finish()
            }
            WidgetDecision.REQUEST_PERMISSION -> {
                requestedProfileId = command.profileId
                permissionPending = true
                vpnPermission.launch(checkNotNull(prepare))
            }
            WidgetDecision.NONE -> { requestWidgetRefresh(); finish() }
        }
    }

    private companion object {
        const val PENDING_PROFILE = "pending_profile"
        const val PERMISSION_PENDING = "permission_pending"
    }
}
