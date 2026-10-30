package com.foxhole.vpn

import com.foxhole.vpn.profile.VpnProfileImporter
import com.foxhole.vpn.storage.ProfileStoreState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveProfileImportTest {
    private val importer = VpnProfileImporter()
    private val first = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Owned"
    private val second = first.replace("1.1.1.1:443", "8.8.8.8:443")

    @Test
    fun aDistinctParsedAdditionCommitsWithoutReplacingTheSelectedProfileOrCommand() = runTest {
        val active = importer.import(first).profiles.single()
        val added = importer.import(second).profiles.single()
        val before = ProfileStoreState(profiles = listOf(active), selectedProfileId = active.id)
        val candidate = before.copy(profiles = before.profiles + added)
        val commands = ConnectionCommandFence()
        val ticket = commands.reserveStart()
        commands.dispatch(ticket) {}
        val owner = ProfileMutationOwner(ticket, VpnPhase.CONNECTED, true, false)
        var disk = before
        var published = before
        assertFalse(importRequiresDisconnect(before, candidate, owner))
        val result = commitProfileMutation(before, candidate, { transform -> transform(disk).also { disk = it } }, {
            !importRequiresDisconnect(before, candidate, owner) && profileMutationAdmitted(owner, owner, false, null)
        }) {
            assertEquals(ProfileMutationAction.KEEP, profileMutationAction(owner, false, false, null))
            published = it
            true
        }
        assertEquals(ProfileMutationResult.APPLIED, result)
        assertEquals(candidate, disk)
        assertEquals(disk, published)
        assertEquals(active, published.profiles.single { it.id == published.selectedProfileId })
        assertEquals(ticket, commands.currentGeneration())
        assertFalse(commands.hasPendingStart())
    }

    @Test
    fun aRealStableSubscriptionIdWithChangedActiveConfigIsRefusedBeforeWriting() = runTest {
        val active = importer.importSubscription(first, "https://example.com/subscription", false).profiles.single()
        val replacement = importer.importSubscription(second, "https://example.com/subscription", false).profiles.single()
        assertEquals(active.id, replacement.id)
        assertFalse(active.normalizedConfigJson == replacement.normalizedConfigJson)
        val before = ProfileStoreState(profiles = listOf(active), selectedProfileId = active.id)
        val candidate = before.copy(profiles = listOf(replacement))
        val owner = ProfileMutationOwner(17L, VpnPhase.CONNECTED, true, false)
        var writes = 0
        var publications = 0
        val result = commitProfileMutation(before, candidate, { transform ->
            writes++
            transform(before)
        }, {
            !importRequiresDisconnect(before, candidate, owner)
        }) {
            publications++
            true
        }
        assertEquals(ProfileMutationResult.SUPERSEDED, result)
        assertEquals(0, writes)
        assertEquals(0, publications)
        assertEquals(active.id, before.selectedProfileId)
    }

    @Test
    fun pendingTransitionalAndRetainedErrorOwnersProtectBothConfigAndSelection() {
        val active = importer.import(first).profiles.single()
        val other = importer.import(second).profiles.single()
        val before = ProfileStoreState(profiles = listOf(active, other), selectedProfileId = active.id)
        val selection = before.copy(selectedProfileId = other.id)
        val config = before.copy(profiles = listOf(active.copy(normalizedConfigJson = other.normalizedConfigJson), other))
        val owners = listOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.CONNECTED, VpnPhase.STOPPING, VpnPhase.ERROR)
            .map { ProfileMutationOwner(17L, it, true, false) } + ProfileMutationOwner(17L, VpnPhase.IDLE, false, true)
        owners.forEach {
            assertTrue(importRequiresDisconnect(before, selection, it))
            assertTrue(importRequiresDisconnect(before, config, it))
        }
    }

    @Test
    fun metadataAndUnselectedAdditionsStayAllowedWhileTheTunnelIsOwnedOrPending() {
        val active = importer.import(first).profiles.single()
        val other = importer.import(second).profiles.single()
        val before = ProfileStoreState(profiles = listOf(active), selectedProfileId = active.id)
        val candidate = before.copy(profiles = listOf(active.copy(name = "Updated title", subscriptionExpiresAt = 1_900_000_000L), other))
        listOf(
            ProfileMutationOwner(17L, VpnPhase.CONNECTED, true, false),
            ProfileMutationOwner(18L, VpnPhase.CONNECTING, false, true),
            ProfileMutationOwner(19L, VpnPhase.STOPPING, true, false),
        ).forEach { assertFalse(importRequiresDisconnect(before, candidate, it)) }
        assertEquals(active.id, candidate.selectedProfileId)
        assertEquals(active.normalizedConfigJson, candidate.profiles.first().normalizedConfigJson)
    }

    @Test
    fun disconnectedReplacementIsAllowedAndExplicitRefreshKeepsItsExistingReconnectPolicy() {
        val active = importer.import(first).profiles.single()
        val replacement = active.copy(normalizedConfigJson = importer.import(second).profiles.single().normalizedConfigJson)
        val before = ProfileStoreState(profiles = listOf(active), selectedProfileId = active.id)
        val candidate = before.copy(profiles = listOf(replacement))
        listOf(VpnPhase.IDLE, VpnPhase.ERROR).forEach {
            assertFalse(importRequiresDisconnect(before, candidate, ProfileMutationOwner(17L, it, false, false)))
        }
        val connected = ProfileMutationOwner(17L, VpnPhase.CONNECTED, true, false)
        assertTrue(profileMutationAdmitted(connected, connected, true, null))
        assertEquals(ProfileMutationAction.RECONNECT, profileMutationAction(connected, false, true, null))
    }
}
