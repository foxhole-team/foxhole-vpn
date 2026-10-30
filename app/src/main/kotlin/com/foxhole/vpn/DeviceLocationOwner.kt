package com.foxhole.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class DeviceLocationOwner<N : Any>(
    private val scope: CoroutineScope,
    private val lookup: suspend (N) -> VpnLocation,
    private val elapsedRealtime: () -> Long,
    private val onConfirmedHome: (VpnLocation) -> Unit = {},
) {
    private val mutable = MutableStateFlow(VpnDeviceLocation())
    val state: StateFlow<VpnDeviceLocation> = mutable
    private val events = Channel<Event<N>>(Channel.UNLIMITED)
    private var foreground = false
    private var tunnelActive = false
    private var network: N? = null
    private var generation = 0L
    private var request: Job? = null

    init {
        scope.launch {
            for (event in events) when (event) {
                is Event.Foreground -> if (foreground != event.value) {
                    foreground = event.value
                    refresh()
                }
                is Event.Tunnel -> if (tunnelActive != event.value) {
                    tunnelActive = event.value
                    refresh()
                }
                is Event.Network -> if (network != event.value) {
                    network = event.value
                    refresh()
                }
                Event.Refresh -> if (request?.isActive != true) refresh()
                is Event.Restore -> if (mutable.value.location == null && request?.isActive != true &&
                    mutable.value.status == DeviceLocationStatus.UNKNOWN) {
                    mutable.value = VpnDeviceLocation(event.location, DeviceLocationStatus.CACHED)
                }
                is Event.WidgetConfirmed -> if (!foreground && !tunnelActive && request?.isActive != true &&
                    mutable.value == event.expected && event.admitted()) {
                    mutable.value = VpnDeviceLocation(event.location, DeviceLocationStatus.CURRENT, elapsedRealtime())
                }
                is Event.Completed -> if (event.generation == generation && foreground && !tunnelActive) {
                    val location = event.result.getOrNull()
                    mutable.value = if (location == null) {
                        mutable.value.copy(status = DeviceLocationStatus.UNAVAILABLE)
                    } else {
                        VpnDeviceLocation(location, DeviceLocationStatus.CURRENT, elapsedRealtime())
                    }
                    if (location != null) onConfirmedHome(location)
                }
            }
        }
    }

    fun setForeground(value: Boolean) { events.trySend(Event.Foreground(value)) }
    fun setTunnelActive(value: Boolean) { events.trySend(Event.Tunnel(value)) }
    fun networkChanged(value: N?) { events.trySend(Event.Network(value)) }
    fun refreshNow() { events.trySend(Event.Refresh) }
    fun restoreCached(location: VpnLocation) { events.trySend(Event.Restore(location)) }
    fun confirmWidgetHome(location: VpnLocation, expected: VpnDeviceLocation, admitted: () -> Boolean) {
        events.trySend(Event.WidgetConfirmed(location, expected, admitted))
    }

    private fun refresh() {
        val token = ++generation
        request?.cancel()
        request = null
        if (!foreground || tunnelActive) {
            mutable.value = mutable.value.copy(status = if (mutable.value.location == null) {
                DeviceLocationStatus.UNKNOWN
            } else DeviceLocationStatus.CACHED)
            return
        }
        val current = network
        if (current == null) {
            mutable.value = mutable.value.copy(status = DeviceLocationStatus.UNAVAILABLE)
            return
        }
        mutable.value = mutable.value.copy(status = DeviceLocationStatus.LOADING)
        request = scope.launch {
            val result = try { Result.success(lookup(current)) }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                Result.failure(cancelled)
            } catch (failure: Exception) { Result.failure(failure) }
            events.send(Event.Completed(token, result))
        }
    }

    private sealed interface Event<out N> {
        data object Refresh : Event<Nothing>
        data class Restore(val location: VpnLocation) : Event<Nothing>
        data class WidgetConfirmed(
            val location: VpnLocation,
            val expected: VpnDeviceLocation,
            val admitted: () -> Boolean,
        ) : Event<Nothing>
        data class Foreground(val value: Boolean) : Event<Nothing>
        data class Tunnel(val value: Boolean) : Event<Nothing>
        data class Network<N>(val value: N?) : Event<N>
        data class Completed(val generation: Long, val result: Result<VpnLocation>) : Event<Nothing>
    }
}
