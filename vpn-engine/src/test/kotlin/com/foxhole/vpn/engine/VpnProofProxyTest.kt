package com.foxhole.vpn.engine

import org.junit.Assert.*
import org.junit.Test

class VpnProofProxyTest {
    @Test fun acceptsOnlyTheOwnedLoopbackPrimaryListener() {
        val valid = """{"inbounds":[{"name":"vpn-connection-proof","state":"ready","upstream":"profile","http_address":"127.0.0.1:45678"}]}"""
        assertEquals(45678, VpnProofProxy.ready(valid, "secret")?.port)
        assertNull(VpnProofProxy.ready(valid.replace("profile", "direct"), "secret"))
        assertNull(VpnProofProxy.ready(valid.replace("127.0.0.1", "0.0.0.0"), "secret"))
        assertNull(VpnProofProxy.ready(valid.replace("ready", "arming"), "secret"))
        assertNull(VpnProofProxy.ready(valid.replace("vpn-connection-proof", "other"), "secret"))
    }

    @Test fun proofCredentialsAreFreshAndNotPresentInDiagnostics() {
        val first = VpnProofProxy.create()
        val second = VpnProofProxy.create()
        assertEquals(64, first.password.length)
        assertNotEquals(first.password, second.password)
        assertFalse(first.toString().contains(first.password))
        assertTrue(first.config().contains("\"allow_anonymous\":false"))
    }
}
