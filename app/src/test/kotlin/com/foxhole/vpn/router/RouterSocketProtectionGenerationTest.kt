package com.foxhole.vpn.router

import java.lang.reflect.Proxy
import java.net.Socket
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSocketProtectionGenerationTest {
    @Test fun ownerGenerationChangingDuringWifiBindClosesEvenWhileVpnStaysActive() {
        var generation = 1L
        var approvedGeneration = 0L
        val protection = Proxy.newProxyInstance(RouterSocketProtection::class.java.classLoader,
            arrayOf(RouterSocketProtection::class.java)) { _, method, _ ->
            when (method.name) {
                "protect" -> { approvedGeneration = generation; true }
                "isCurrent" -> approvedGeneration == generation
                else -> error("unexpected protection method")
            }
        } as RouterSocketProtection
        val socket = Socket()
        try {
            assertThrows(RouterVpnSocketUnavailable::class.java) {
                createRouterWifiSocket({ true }, protection, { generation++ }, { socket })
            }
            assertTrue(socket.isClosed)
        } finally { socket.close() }
    }
}
