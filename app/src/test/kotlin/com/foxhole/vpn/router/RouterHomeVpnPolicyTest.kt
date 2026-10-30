package com.foxhole.vpn.router

import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouterHomeVpnPolicyTest {
    @Test fun everySavedPairingAndOptInConditionIsRequired() {
        assertTrue(routerHomeVpnManagementAllowed(true, true, true, true, true))
        for (disabled in 0..4) {
            val flags = MutableList(5) { it != disabled }
            assertFalse(routerHomeVpnManagementAllowed(flags[0], flags[1], flags[2], flags[3], flags[4]))
        }
    }

    @Test fun missingOrInvalidTargetRouteCannotUseAVpnIndicatorAsProof() {
        assertFalse(routerHomeVpnRouteUsable(emptyList()))
        assertFalse(routerHomeVpnRouteUsable(listOf(RouterHomeVpnRoute(-1, true))))
        assertFalse(routerHomeVpnRouteUsable(listOf(RouterHomeVpnRoute(33, true))))
    }

    @Test fun moreSpecificUnreachableRouteOverridesAFullTunnelRoute() {
        assertFalse(routerHomeVpnRouteUsable(listOf(RouterHomeVpnRoute(0, true), RouterHomeVpnRoute(24, false))))
        assertFalse(routerHomeVpnRouteUsable(listOf(RouterHomeVpnRoute(24, true), RouterHomeVpnRoute(24, false))))
    }

    @Test fun targetSpecificRouteAndFullTunnelRouteAreCandidatesForPinnedAuthentication() {
        assertTrue(routerHomeVpnRouteUsable(listOf(RouterHomeVpnRoute(0, true))))
        assertTrue(routerHomeVpnRouteUsable(listOf(RouterHomeVpnRoute(0, false), RouterHomeVpnRoute(32, true))))
    }

    @Test fun socketUsesOnlyTheVpnBinderAndRechecksItsNetwork() {
        val events = mutableListOf<String>()
        val socket = TrackingSocket()
        val returned = createRouterHomeVpnSocket({ events += "check" }, { bound ->
            assertSame(socket, bound)
            events += "bind-vpn"
        }, { events += "create"; socket })
        assertSame(socket, returned)
        assertEquals(listOf("check", "create", "bind-vpn", "check"), events)
        assertFalse(socket.wasClosed)
    }

    @Test fun unavailableVpnFailsBeforeCreatingASocket() {
        var created = false
        expectIoFailure {
            createRouterHomeVpnSocket({ throw IOException("synthetic missing VPN") }, { fail("must not bind") },
                { created = true; TrackingSocket() })
        }
        assertFalse(created)
    }

    @Test fun bindFailureClosesTheSocketWithoutAnUnderlyingNetworkFallback() {
        val socket = TrackingSocket()
        var binds = 0
        expectIoFailure {
            createRouterHomeVpnSocket({}, { binds++; throw IOException("synthetic bind failure") }, { socket })
        }
        assertEquals(1, binds)
        assertTrue(socket.wasClosed)
    }

    @Test fun lossOfVpnDuringBindingClosesTheSocket() {
        val socket = TrackingSocket()
        var checks = 0
        expectIoFailure {
            createRouterHomeVpnSocket({ if (++checks == 2) throw IOException("synthetic lost VPN") }, {}, { socket })
        }
        assertEquals(2, checks)
        assertTrue(socket.wasClosed)
    }

    private fun expectIoFailure(action: () -> Unit) {
        try { action(); fail("expected an IOException") } catch (_: IOException) {}
    }

    private class TrackingSocket : Socket() {
        var wasClosed = false
        override fun close() { wasClosed = true }
    }
}
