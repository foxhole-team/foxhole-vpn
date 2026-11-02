package com.foxhole.vpn.appupdates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnUpdateResumePolicyTest {
    private val digest = vpnUpdateConfigurationDigest(listOf("fixture-config", "fixture-option"))
    private val ticket = VpnUpdateResumeTicket("active-profile", digest, 43, 1_000)

    @Test fun onlyTheSameProfileConfigurationAndInstalledTargetCanResume() {
        assertTrue(vpnUpdateResumeAllowed(ticket, 43, "active-profile", digest, 2_000))
        assertFalse(vpnUpdateResumeAllowed(ticket, 42, "active-profile", digest, 2_000))
        assertFalse(vpnUpdateResumeAllowed(ticket, 43, "selected-but-not-active", digest, 2_000))
        assertFalse(vpnUpdateResumeAllowed(ticket, 43, "active-profile", "b".repeat(64), 2_000))
        assertFalse(vpnUpdateResumeAllowed(ticket, 43, null, null, 2_000))
    }

    @Test fun staleAndClockRolledBackHandoffsRequireManualConnection() {
        assertFalse(vpnUpdateResumeAllowed(ticket, 43, "active-profile", digest, 999))
        assertFalse(vpnUpdateResumeAllowed(ticket, 43, "active-profile", digest, 3_601_001))
    }

    @Test fun changingTheSelectedProtocolChangesTheConfigurationIdentity() {
        assertFalse(digest == vpnUpdateConfigurationDigest(listOf("fixture-config", "other-option")))
    }
}
