package com.foxhole.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCueClaimsTest {
    @Test fun reopeningTheHomeCannotReplayTheSameConnectionCue() {
        val claims = ConnectionCueClaims()
        assertFalse(claims.claim(0))
        assertTrue(claims.claim(1))
        assertFalse(claims.claim(1))
    }

    @Test fun aNewConnectionCanClaimButAnOlderQueuedCueCannot() {
        val claims = ConnectionCueClaims()
        assertTrue(claims.claim(2))
        assertFalse(claims.claim(1))
        assertTrue(claims.claim(3))
    }
}
