package com.foxhole.vpn.widget

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val widgetRefreshRevision = MutableStateFlow(0L)

internal fun requestWidgetRefresh() {
    widgetRefreshRevision.update { it + 1L }
}

internal fun startWidgetUpdates(application: VpnApplication, scope: CoroutineScope): Job = scope.launch {
    val configurationObserver = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = requestWidgetRefresh()
        override fun onLowMemory() = Unit
    }
    val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = requestWidgetRefresh()
    }
    application.registerComponentCallbacks(configurationObserver)
    application.contentResolver.registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver,
    )
    val licenseUpdates = launch {
        application.licenseConsent.state.map { it.accepted }.distinctUntilChanged().collect { requestWidgetRefresh() }
    }
    try {
        widgetRenderRequests(application.controller.state, application.controller.settings, widgetRefreshRevision)
            .collect { (state, settings) ->
                renderStatusWidgets(application, state, settings)
                renderFoxWidgets(application, state, settings)
            }
    } finally {
        licenseUpdates.cancel()
        application.unregisterComponentCallbacks(configurationObserver)
        application.contentResolver.unregisterContentObserver(motionObserver)
    }
}

internal fun widgetRenderRequests(
    states: Flow<VpnUiState>,
    settings: Flow<VpnSettings>,
    revisions: Flow<Long>,
): Flow<Pair<VpnUiState, VpnSettings>> = combine(states, settings, revisions) { state, preferences, revision ->
    WidgetRenderRequest(state, preferences, revision)
}.distinctUntilChangedBy { it.key }.map { it.state to it.settings }

private data class WidgetRenderRequest(val state: VpnUiState, val settings: VpnSettings, val revision: Long) {
    val key = WidgetRenderKey(
        state.isReady, state.phase, state.hasOwnedTunnel, state.selectedProfile?.id,
        widgetLocation(state), settings.theme, settings.language, revision,
    )
}

private data class WidgetRenderKey(
    val isReady: Boolean,
    val phase: VpnPhase,
    val hasOwnedTunnel: Boolean,
    val selectedProfileId: String?,
    val location: WidgetLocation?,
    val theme: VpnTheme,
    val language: VpnLanguage,
    val revision: Long,
)
