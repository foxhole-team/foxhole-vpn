package com.foxhole.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineNetworkPolicyTest {
    private val ownUid = 10220
    private val cellular = candidate(103, cellular = true)
    private val wifi = candidate(133, wifi = true)

    @Test fun pixelDefaultWifiWinsOverEarlierCellularCandidate() {
        assertEquals(133L, preferredUnderlyingNetwork(133, listOf(cellular, wifi)))
        assertEquals(133L, preferredUnderlyingNetwork(133, listOf(wifi, cellular)))
    }

    @Test fun vpnDefaultFallsBackToValidatedUnrestrictedPhysicalNetwork() {
        val vpn = candidate(136, vpn = true, ownerUid = ownUid)
        assertEquals(133L, preferredUnderlyingNetwork(136, listOf(cellular.copy(validated = false), vpn, wifi)))
        assertEquals(103L, preferredUnderlyingNetwork(null, listOf(wifi.copy(validated = false), cellular)))
    }

    @Test fun unusableCandidatesCannotBecomeAnUpstream() {
        assertNull(
            preferredUnderlyingNetwork(133, listOf(wifi.copy(internet = false), cellular.copy(unrestricted = false)))
        )
        assertNull(preferredUnderlyingNetwork(136, listOf(candidate(136, vpn = true, ownerUid = ownUid))))
        assertNull(preferredUnderlyingNetwork(null, emptyList()))
    }

    @Test fun eligibleDefaultDoesNotRequireTheSystemValidationBit() {
        assertEquals(133L, preferredUnderlyingNetwork(133, listOf(cellular, wifi.copy(validated = false))))
    }

    @Test fun modernVpnDetectionRejectsForeignAndRedactedOwners() {
        val foreign = candidate(137, vpn = true, ownerUid = 10111)
        val redacted = candidate(138, vpn = true, ownerUid = -1)
        assertNull(ownedVpnNetwork(listOf(foreign, redacted, wifi), ownUid, 37, emptySet()))
        assertFalse(isOwnedVpnNetwork(foreign, ownUid, 37))
    }

    @Test fun uniqueOwnedVpnMayReuseItsPreviousNetworkIdWithoutValidationBit() {
        val owned = candidate(136, vpn = true, ownerUid = ownUid, validated = false)
        assertEquals(136L, ownedVpnNetwork(listOf(owned), ownUid, 37, setOf(136)))
        assertTrue(isOwnedVpnNetwork(owned, ownUid, 37))
    }

    @Test fun newOwnedVpnIsPreferredAndAmbiguousOldCandidatesFailClosed() {
        val old = candidate(135, vpn = true, ownerUid = ownUid)
        val new = candidate(136, vpn = true, ownerUid = ownUid)
        assertEquals(136L, ownedVpnNetwork(listOf(old, new), ownUid, 37, setOf(135)))
        assertNull(ownedVpnNetwork(listOf(old, new), ownUid, 37, setOf(135, 136)))
    }

    @Test fun api26RetainsNewNetworkFallbackWithoutClaimingOwnerUidProof() {
        val old = candidate(135, vpn = true)
        val fresh = candidate(136, vpn = true)
        assertEquals(136L, ownedVpnNetwork(listOf(old, fresh), ownUid, 26, setOf(135)))
        assertNull(ownedVpnNetwork(listOf(old), ownUid, 26, setOf(135)))
    }

    private fun candidate(
        handle: Long,
        vpn: Boolean = false,
        internet: Boolean = true,
        unrestricted: Boolean = true,
        validated: Boolean = true,
        wifi: Boolean = false,
        cellular: Boolean = false,
        ownerUid: Int? = null,
    ) = EngineNetworkCandidate(handle, vpn, internet, unrestricted, validated, wifi, false, false, cellular, ownerUid)
}
