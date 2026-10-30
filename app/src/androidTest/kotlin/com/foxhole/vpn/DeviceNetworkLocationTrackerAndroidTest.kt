package com.foxhole.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceNetworkLocationTrackerAndroidTest {
    @Test
    fun actualCapabilitySnapshotsAndNullUseThePublicSdkBoundary() {
        assertFalse(deviceHomeNetworkAllowed(null))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val networks = connectivity.allNetworks.toList() + listOfNotNull(connectivity.activeNetwork)
        networks.distinct().forEach { network ->
            val snapshot = connectivity.getNetworkCapabilities(network)
            val allowed = deviceHomeNetworkAllowed(snapshot)
            when {
                snapshot == null -> assertFalse(allowed)
                snapshot.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> assertFalse(allowed)
                !snapshot.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) -> assertFalse(allowed)
                else -> assertTrue(allowed)
            }
        }
    }
}
