package com.foxhole.vpn

import com.foxhole.vpn.engine.VpnApplicationScope
import com.foxhole.vpn.storage.ProfileStoreState
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppRoutingTest {
    private val owner = "com.foxhole.vpn"
    private val vpn = VpnAppRule("org.example.vpn", VpnAppRoute.VPN)
    private val direct = VpnAppRule("org.example.direct", VpnAppRoute.DIRECT)

    @Test fun oldEncryptedSettingsKeepTheirChoicesAndReceiveOnlyMissingDefaults() {
        val settings = Json.decodeFromString<VpnSettings>("""{"autoRefreshSubscriptions":false,"autoCheckConnectionData":false}""")
        assertFalse(settings.autoRefreshSubscriptions)
        assertFalse(settings.autoCheckConnectionData)
        assertEquals(VpnRoutingMode.WHOLE_DEVICE, settings.appRouting.mode)
        assertTrue(settings.appRouting.defaultThroughVpn)
        assertTrue(settings.appRouting.atomicApply)
        assertFalse(settings.appRouting.confirmChanges)
    }

    @Test fun wholeDeviceIgnoresRulesAndRetainsTheSelectedDefaultForLater() {
        val routing = VpnAppRouting(defaultThroughVpn = false, rules = listOf(vpn, direct))
        assertEquals(VpnApplicationScope.WholeDevice, routing.applicationScope(owner))
        assertFalse(routing.defaultThroughVpn)
    }

    @Test fun selectedDefaultOnExcludesOnlyExplicitDirectRules() {
        val routing = VpnAppRouting(VpnRoutingMode.SELECTED_APPS, rules = listOf(vpn, direct))
        assertEquals(VpnApplicationScope.Exclude(setOf(direct.packageName)), routing.applicationScope(owner))
    }

    @Test fun selectedDefaultOffIncludesOnlyExplicitVpnRules() {
        val routing = VpnAppRouting(VpnRoutingMode.SELECTED_APPS, false, listOf(vpn, direct))
        assertEquals(VpnApplicationScope.Include(setOf(vpn.packageName)), routing.applicationScope(owner))
    }

    @Test fun selectedEmptyDefaultOffRemainsAnExplicitIncludeScope() {
        val routing = VpnAppRouting(VpnRoutingMode.SELECTED_APPS, false)
        assertEquals(VpnApplicationScope.Include(emptySet()), routing.applicationScope(owner))
    }

    @Test fun equivalentEffectivePoliciesDoNotRequireATunnelRestart() {
        val whole = VpnAppRouting()
        val selected = whole.copy(mode = VpnRoutingMode.SELECTED_APPS, rules = listOf(vpn))
        assertEquals(whole.applicationScope(owner), selected.applicationScope(owner))
        assertEquals(selected.applicationScope(owner), selected.copy(atomicApply = false, confirmChanges = true).applicationScope(owner))
    }

    @Test fun duplicateSelectionUpdatesOneRuleWithoutReorderingOtherRows() {
        val routing = VpnAppRouting(rules = listOf(vpn, direct)).withRule(vpn.packageName, VpnAppRoute.DIRECT)
        assertEquals(listOf(vpn.copy(route = VpnAppRoute.DIRECT), direct), routing.rules)
    }

    @Test fun confirmationAppliesOnlyToModeAndDefaultChanges() {
        val before = VpnAppRouting(confirmChanges = true)
        assertTrue(routingChangeNeedsConfirmation(before, before.copy(mode = VpnRoutingMode.SELECTED_APPS)))
        assertTrue(routingChangeNeedsConfirmation(before, before.copy(defaultThroughVpn = false)))
        assertFalse(routingChangeNeedsConfirmation(before, before.withRule(vpn.packageName, vpn.route)))
        assertFalse(routingChangeNeedsConfirmation(before, before.copy(atomicApply = false)))
        assertFalse(routingChangeNeedsConfirmation(before, before.copy(confirmChanges = false)))
    }

    @Test fun explicitlySavedFalseDefaultsSurviveSerialization() {
        val before = VpnSettings(appRouting = VpnAppRouting(VpnRoutingMode.SELECTED_APPS, false, atomicApply = false))
        assertEquals(before, Json.decodeFromString<VpnSettings>(Json.encodeToString(before)))
    }

    @Test fun atomicOffConfirmsScenarioChangesEvenWithExplicitConfirmationOff() {
        val before = VpnAppRouting(atomicApply = false, confirmChanges = false)
        assertTrue(routingChangeNeedsConfirmation(before, before.copy(mode = VpnRoutingMode.SELECTED_APPS)))
        assertFalse(routingChangeNeedsConfirmation(before, before.withRule(vpn.packageName, vpn.route)))
    }

    @Test fun addingRoutingDefaultsDoesNotReplaceSavedProfilesOrOtherPreferences() {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
        val previous = """{"schema":1,"profiles":[{"id":"fixture","name":"Saved profile","protocol":"VLESS","normalizedConfigJson":"synthetic-config"}],"selectedProfileId":"fixture","settings":{"theme":"NIGHT","language":"RU","autoRefreshSubscriptions":false}}"""
        val before = json.decodeFromString<ProfileStoreState>(previous)
        val next = before.copy(settings = before.settings.copy(appRouting = VpnAppRouting(VpnRoutingMode.SELECTED_APPS, false, listOf(vpn))))
        val reloaded = json.decodeFromString<ProfileStoreState>(json.encodeToString(next))
        assertEquals(before.profiles, reloaded.profiles)
        assertEquals(before.selectedProfileId, reloaded.selectedProfileId)
        assertEquals(VpnTheme.NIGHT, reloaded.settings.theme)
        assertEquals(VpnLanguage.RU, reloaded.settings.language)
        assertFalse(reloaded.settings.autoRefreshSubscriptions)
        assertEquals(next.settings.appRouting, reloaded.settings.appRouting)
    }

    @Test fun routingWriteCannotOutliveANewerStopGeneration() {
        val before = VpnAppRouting()
        val after = VpnAppRouting(VpnRoutingMode.SELECTED_APPS, false, listOf(vpn))
        val changed = before.applicationScope(owner) != after.applicationScope(owner)
        val connected = ProfileMutationOwner(10, VpnPhase.CONNECTED, true, false)
        assertFalse(profileMutationAdmitted(connected, ProfileMutationOwner(11, VpnPhase.STOPPING, true, false), changed, null))
    }
}
