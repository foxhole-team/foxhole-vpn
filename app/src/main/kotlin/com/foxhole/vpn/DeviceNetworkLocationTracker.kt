package com.foxhole.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.foxhole.vpn.engine.EngineLocation
import com.foxhole.vpn.engine.lookupNetworkLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal class DeviceNetworkLocationTracker(
    context: Context,
    scope: CoroutineScope,
    onConfirmedHome: (VpnLocation) -> Unit = {},
) {
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val owner = DeviceLocationOwner<NetworkSnapshot>(scope,
        { lookupNetworkLocation(it.network).asVpnLocation() }, SystemClock::elapsedRealtime, onConfirmedHome)
    val state: StateFlow<VpnDeviceLocation> = owner.state
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var foreground = false
    private var tunnelActive = false
    private var current: NetworkSnapshot? = null
    private var epoch = 0L

    fun setForeground(value: Boolean) = synchronized(lock) {
        if (foreground == value) return@synchronized
        foreground = value
        if (!value) {
            owner.setForeground(false)
            epoch++
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            callback = null
        } else {
            val registration = ++epoch
            updateDefaultNetwork()
            owner.setForeground(true)
            val listener = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = synchronized(lock) {
                    if (registration == epoch && foreground) updateNetwork(network, connectivity.getLinkProperties(network))
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = synchronized(lock) {
                    if (registration == epoch && foreground && current?.network == network) updateNetwork(network, properties)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
                    if (registration == epoch && foreground && connectivity.activeNetwork == network) {
                        updateNetwork(network, connectivity.getLinkProperties(network))
                    }
                }

                override fun onLost(network: Network) = synchronized(lock) {
                    if (registration == epoch && foreground && current?.network == network) updateDefaultNetwork()
                }
            }
            try {
                connectivity.registerDefaultNetworkCallback(listener)
                callback = listener
            } catch (_: RuntimeException) {
                current = null
                owner.networkChanged(null)
            }
        }
    }

    fun setTunnelActive(value: Boolean) = synchronized(lock) {
        if (tunnelActive == value) return@synchronized
        tunnelActive = value
        if (!value && foreground) updateDefaultNetwork()
        owner.setTunnelActive(value)
    }

    fun refreshNow() = synchronized(lock) {
        if (foreground && !tunnelActive) owner.refreshNow()
    }

    fun restoreCached(location: VpnLocation) = owner.restoreCached(location)

    fun widgetHomeNetwork(): NetworkSnapshot? = synchronized(lock) {
        val network = connectivity.activeNetwork ?: return@synchronized null
        if (foreground || tunnelActive || !deviceHomeNetworkAllowed(connectivity.getNetworkCapabilities(network))) return@synchronized null
        NetworkSnapshot(network, connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.toString() }.sorted(), epoch)
    }

    suspend fun lookupWidgetHome(snapshot: NetworkSnapshot): VpnLocation = lookupNetworkLocation(snapshot.network).asVpnLocation()

    fun confirmWidgetHome(location: VpnLocation, expected: VpnDeviceLocation, admitted: () -> Boolean) =
        owner.confirmWidgetHome(location, expected, admitted)

    private fun updateDefaultNetwork() {
        val network = connectivity.activeNetwork
        if (network == null) {
            current = null
            owner.networkChanged(null)
        } else updateNetwork(network, connectivity.getLinkProperties(network))
    }

    private fun updateNetwork(network: Network, properties: LinkProperties?) {
        if (!deviceHomeNetworkAllowed(connectivity.getNetworkCapabilities(network))) {
            current = null
            owner.networkChanged(null)
            return
        }
        val snapshot = NetworkSnapshot(network, properties?.linkAddresses.orEmpty().map { it.toString() }.sorted())
        if (snapshot == current) return
        current = snapshot
        owner.networkChanged(snapshot)
    }

    internal data class NetworkSnapshot(val network: Network, val addresses: List<String>, val foregroundEpoch: Long = 0L)
}

internal fun deviceHomeNetworkAllowed(capabilities: NetworkCapabilities?): Boolean =
    capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

internal fun EngineLocation.asVpnLocation() = VpnLocation(countryCode, city, country, ip, latitude, longitude)
