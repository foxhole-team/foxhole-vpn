package com.foxhole.vpn.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnApplicationScopeTest {
    private val owner = "com.foxhole.vpn"

    @Test fun emptyIncludeStillCapturesTheOwnerAndNeverBecomesAllApplications() {
        val plan = applicationRoutingPlan(VpnApplicationScope.Include(emptySet()), owner)
        assertEquals(listOf(owner), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test fun ownerRemainsInVpnForBoundConnectionValidation() {
        val plan = applicationRoutingPlan(VpnApplicationScope.Exclude(setOf(owner, "org.example.direct")), owner)
        assertEquals(listOf("org.example.direct"), plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test fun explicitVpnPackagesAreCombinedWithOwnerOnly() {
        val plan = applicationRoutingPlan(VpnApplicationScope.Include(setOf("org.example.vpn", owner)), owner)
        assertEquals(listOf(owner, "org.example.vpn"), plan.allowed)
    }

    @Test fun malformedTrustedSettingsFailBeforeBuilderEstablishes() {
        assertThrows(IllegalArgumentException::class.java) {
            applicationRoutingPlan(VpnApplicationScope.Include(setOf("invalid/package")), owner)
        }
    }
}
