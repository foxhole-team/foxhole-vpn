package com.foxhole.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingSuccessNoticeTest {
    private val selected = VpnAppRouting(mode = VpnRoutingMode.SELECTED_APPS)

    @Test fun offlineEditsOnlyAnnounceScenarioChanges() {
        assertNull(routingSuccessNotice(selected, selected.withRule("org.example.app", VpnAppRoute.VPN), false))
        assertEquals(RoutingSuccessNotice.SCENARIO, routingSuccessNotice(VpnAppRouting(), selected, false))
        assertEquals(RoutingSuccessNotice.SCENARIO, routingSuccessNotice(selected, selected.copy(defaultThroughVpn = false), false))
    }

    @Test fun aLiveDeletionHasItsOwnMessageAndOtherRuleChangesReportApplied() {
        val rule = selected.withRule("org.example.app", VpnAppRoute.VPN)
        assertEquals(RoutingSuccessNotice.APP_REMOVED, routingSuccessNotice(rule, selected, true))
        assertEquals(RoutingSuccessNotice.RULES, routingSuccessNotice(selected, rule, true))
        assertEquals(RoutingSuccessNotice.RULES, routingSuccessNotice(rule, rule.withRule("org.example.app", VpnAppRoute.DIRECT), true))
    }

    @Test fun preferencesAndRuleOrderingDoNotPretendToSwitchTraffic() {
        assertNull(routingSuccessNotice(selected, selected.copy(atomicApply = false, confirmChanges = true), true))
        val rules = selected.withRule("org.example.first", VpnAppRoute.VPN).withRule("org.example.second", VpnAppRoute.DIRECT)
        assertNull(routingSuccessNotice(rules, rules.copy(rules = rules.rules.reversed()), true))
        assertNull(routingSuccessNotice(selected, selected, true))
    }
}
