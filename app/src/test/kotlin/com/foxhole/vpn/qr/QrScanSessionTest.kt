package com.foxhole.vpn.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanSessionTest {
    @Test fun openingRequestsPermissionOnceAndDenialRequiresAnExplicitRetry() {
        val session = QrScanSession()
        assertFalse(session.shouldRequestOnOpen)
        session.setResumed(true)
        assertTrue(session.shouldRequestOnOpen)
        assertTrue(session.requestPermission())
        assertFalse(session.requestPermission())
        session.permissionResult(false)
        session.setResumed(false)
        session.setResumed(true)
        assertFalse(session.shouldRequestOnOpen)
        assertFalse(session.canScan)
        assertTrue(session.requestPermission())
    }

    @Test fun recreationKeepsTheRequestMarkerWithoutRepeatingADeniedPrompt() {
        val session = QrScanSession(permissionRequested = true)
        session.setResumed(true)
        assertFalse(session.shouldRequestOnOpen)
        session.setPermission(true)
        assertTrue(session.canScan)
    }

    @Test fun pausedOrRevokedSessionsCannotDeliverAResultEvenAfterResume() {
        val session = ready()
        val old = requireNotNull(session.token)
        session.setResumed(false)
        assertNull(session.claim(old, "vless://owned.invalid"))
        session.setResumed(true)
        val current = requireNotNull(session.token)
        assertNotEquals(old, current)
        assertNull(session.claim(old, "vless://owned.invalid"))
        session.setPermission(false)
        assertNull(session.claim(current, "vless://owned.invalid"))
    }

    @Test fun oneAcceptedPayloadStopsScanningAndCannotBeDeliveredTwice() {
        val session = ready()
        val token = requireNotNull(session.token)
        assertEquals("vless://owned.invalid", session.claim(token, "  vless://owned.invalid  "))
        assertFalse(session.canScan)
        assertNull(session.claim(token, "vless://second.invalid"))
        session.setResumed(false)
        session.setResumed(true)
        assertFalse(session.canScan)
    }

    @Test fun blankAndOversizedUtf8PayloadsDoNotConsumeTheSession() {
        val session = ready()
        val token = requireNotNull(session.token)
        for (raw in listOf(null, "  \n", "a".repeat(16385), "€".repeat(6000))) assertNull(session.claim(token, raw))
        assertTrue(session.canScan)
        assertEquals("owned", session.claim(token, "owned"))
    }

    @Test fun cameraFailureNeedsRetryAndStaleFailuresCannotStopTheNewSession() {
        val session = ready()
        val old = requireNotNull(session.token)
        assertTrue(session.cameraFailed(old))
        assertFalse(session.canScan)
        assertTrue(session.snapshot().cameraUnavailable)
        session.retryCamera()
        assertTrue(session.canScan)
        assertFalse(session.cameraFailed(old))
        assertTrue(session.canScan)
    }

    @Test fun cancellingInvalidatesAnyQueuedResult() {
        val session = ready()
        val token = requireNotNull(session.token)
        session.cancel()
        assertFalse(session.canScan)
        assertNull(session.claim(token, "vless://owned.invalid"))
        session.setResumed(true)
        assertFalse(session.canScan)
    }

    private fun ready() = QrScanSession().apply { setPermission(true); setResumed(true) }
}
