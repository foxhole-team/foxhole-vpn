package com.foxhole.vpn.router

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class RouterWifiGatewayTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
    private fun v6(): InetAddress = InetAddress.getByAddress(ByteArray(16).apply {
        this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 1
    })

    @Test fun anIpv6DefaultRouteCannotHideThePrivateIpv4Router() {
        assertEquals("10.55.0.1", routerWifiGateway(listOf(v6(), v4(10, 55, 0, 1))))
        assertEquals("10.55.0.1", routerWifiGateway(listOf(v4(10, 55, 0, 1), v6())))
    }

    @Test fun ineligibleAndDuplicateGatewaysAreFilteredBeforeUniqueness() {
        val gateway = v4(10, 55, 0, 1)
        assertEquals("10.55.0.1", routerWifiGateway(listOf(v4(192, 0, 2, 1), gateway, gateway)))
        assertNull(routerWifiGateway(listOf(v4(192, 0, 2, 1), v6())))
        assertNull(routerWifiGateway(emptyList()))
    }

    @Test fun twoDifferentPrivateRoutersRemainAmbiguous() {
        assertNull(routerWifiGateway(listOf(v4(10, 55, 0, 1), v4(10, 56, 0, 1))))
    }
}
