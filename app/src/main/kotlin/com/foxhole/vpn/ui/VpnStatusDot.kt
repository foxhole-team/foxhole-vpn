package com.foxhole.vpn.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

@Composable
internal fun VpnStatusDot(color: Color) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val alpha = remember { mutableFloatStateOf(1f) }
    DisposableEffect(resolver, lifecycle) {
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        var pulseJob: Job? = null
        fun updatePulse() {
            pulseJob?.cancel()
            pulseJob = null
            alpha.floatValue = 1f
            val durationScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            if (!durationScale.isFinite() || durationScale <= 0f || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            val periodNanos = 3_600_000_000.0 * durationScale.toDouble()
            pulseJob = scope.launch {
                val started = System.nanoTime()
                while (isActive) {
                    // This small pulse needs no display-rate frame clock.
                    delay(125)
                    val phase = ((System.nanoTime() - started).toDouble() % periodNanos) / periodNanos
                    alpha.floatValue = (0.91 + 0.09 * cos(2.0 * PI * phase)).toFloat()
                }
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updatePulse()
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> updatePulse() }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(lifecycleObserver)
        updatePulse()
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
            scope.cancel()
            alpha.floatValue = 1f
        }
    }
    val diameter = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toDp() * 0.73f }
    Canvas(Modifier.size(diameter).graphicsLayer { this.alpha = alpha.floatValue }.testTag("vpn-status-dot")) {
        drawCircle(color)
    }
}
