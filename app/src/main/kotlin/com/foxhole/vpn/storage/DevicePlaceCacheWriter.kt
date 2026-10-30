package com.foxhole.vpn.storage

import com.foxhole.vpn.VpnLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class DevicePlaceCacheWriter(
    scope: CoroutineScope,
    awaitReady: suspend () -> Unit,
    save: suspend (StoredDevicePlace) -> Unit,
) {
    private val places = Channel<StoredDevicePlace>(Channel.CONFLATED)

    init {
        scope.launch {
            awaitReady()
            var attempted: StoredDevicePlace? = null
            for (place in places) {
                if (place == attempted) continue
                attempted = place
                try { save(place) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }

    fun confirmedHome(location: VpnLocation) {
        storedDevicePlace(location)?.let(places::trySend)
    }
}
