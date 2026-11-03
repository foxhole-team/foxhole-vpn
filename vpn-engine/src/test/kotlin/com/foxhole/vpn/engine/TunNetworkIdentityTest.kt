package com.foxhole.vpn.engine

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunNetworkIdentityTest {
    private val targetDns = InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3))
    private val barrierDns = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 53))

    @Test fun extractsOnlyTheKernelInterfaceField() {
        assertEquals("tun12", tunInterfaceName("pos:\t0\nflags:\t0100002\niff:\ttun12\n"))
        assertNull(tunInterfaceName("pos:\t0\n"))
        assertNull(tunInterfaceName("iff:\t../tun0\n"))
        assertNull(tunInterfaceName("iff:\t\n"))
    }

    @Test fun rejectsTheRetiredTunnelEvenWhenItHasTheSameOwnerAndDns() {
        assertFalse(matchesTunNetwork("tun2", "tun0", setOf(targetDns), listOf(targetDns)))
    }

    @Test fun waitsForBarrierDnsToBeReplacedEvenWhenAndroidReusesTheAgent() {
        assertFalse(matchesTunNetwork("tun2", "tun2", setOf(targetDns), listOf(barrierDns)))
        assertTrue(matchesTunNetwork("tun2", "tun2", setOf(targetDns), listOf(targetDns)))
    }

    @Test fun missingIdentityOrDnsNeverAcknowledgesAHandover() {
        assertFalse(matchesTunNetwork(null, "tun2", setOf(targetDns), listOf(targetDns)))
        assertFalse(matchesTunNetwork("tun2", null, setOf(targetDns), listOf(targetDns)))
        assertFalse(matchesTunNetwork("tun2", "tun2", setOf(targetDns), emptyList()))
        assertFalse(matchesTunNetwork("tun2", "tun2", emptySet(), emptyList()))
    }

    @Test fun comparesAddressBytesRatherThanIpv6PresentationOrDnsOrder() {
        val compact = InetAddress.getByName("fd00::53")
        val expanded = InetAddress.getByName("fd00:0:0:0:0:0:0:53")
        assertTrue(matchesTunNetwork("tun2", "tun2", setOf(compact, targetDns), listOf(targetDns, expanded)))
    }
}
