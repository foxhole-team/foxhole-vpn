package com.foxhole.vpn

import com.foxhole.vpn.profile.VpnProfileImporter
import com.foxhole.vpn.profile.withSelectedProtocol
import com.foxhole.vpn.refresh.RefreshOutcome
import com.foxhole.vpn.refresh.SubscriptionRefreshTarget
import com.foxhole.vpn.refresh.proposeSubscriptionRefresh
import com.foxhole.vpn.refresh.subscriptionRefreshTargets
import com.foxhole.vpn.storage.ProfileStoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticSubscriptionMergeTest {
    private val importer = VpnProfileImporter()
    private val url = "https://example.com/subscription?token=synthetic"
    private val first = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#One"
    private val second = first.replace("1.1.1.1:443", "8.8.8.8:443")

    @Test fun groupsDeduplicateTheActualSourceAndKeepConsentConservative() {
        val profile = importer.importSubscription(first, url, false).profiles.single()
        val state = ProfileStoreState(profiles = listOf(profile, profile.copy(id = "other", insecureTlsConsent = true)))
        val groups = subscriptionRefreshTargets(state)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().profiles.size)
        assertFalse(groups.single().allowInsecureTls)
        assertFalse(groups.single().toString().contains("token=synthetic"))
    }

    @Test fun aLegacyNullableOptionIdPreservesItsExactSelectedConfiguration() {
        val body = first + "\n" + "trojan://synthetic-password@8.8.8.8:443?security=tls&sni=example.com#Two"
        val parsed = importer.importSubscription(body, url, false)
        val grouped = parsed.profiles.single()
        val option = grouped.protocolOptions.last()
        val selected = grouped.withSelectedProtocol(option.id).copy(selectedProtocolOptionId = null)
        val state = ProfileStoreState(profiles = listOf(selected), selectedProfileId = selected.id)
        val proposal = proposeSubscriptionRefresh(
            state,
            subscriptionRefreshTargets(state).single(),
            parsed,
            ProfileMutationOwner(7, VpnPhase.CONNECTED, true, false),
            7
        )
        val refreshed = requireNotNull(proposal.candidate).profiles.single()
        assertEquals(option.id, refreshed.selectedProtocolOptionId)
        assertEquals(selected.normalizedConfigJson, refreshed.normalizedConfigJson)
    }

    @Test fun theActorOwnedProfileIsProtectedWhenStoredSelectionAlreadyPointsAtTheReplacement() {
        val running = importer.importSubscription(first, url, false).profiles.single()
        val selected = importer.import(second).profiles.single()
        val state = ProfileStoreState(profiles = listOf(running, selected), selectedProfileId = selected.id)
        val incoming = importer.importSubscription(second, url, false)
        val owner = ProfileMutationOwner(8, VpnPhase.STOPPING, true, true)
        val proposal = proposeSubscriptionRefresh(
            state,
            subscriptionRefreshTargets(state).single(),
            incoming,
            owner,
            8,
            running.id
        )
        assertEquals(RefreshOutcome.DEFERRED, proposal.outcome)
        assertNull(proposal.candidate)
        assertEquals(running.normalizedConfigJson, state.profiles.first().normalizedConfigJson)
    }

    @Test fun changedActiveStableSubscriptionIsDeferredWithoutACandidateWrite() {
        val active = importer.importSubscription(first, url, false).profiles.single()
        val incoming = importer.importSubscription(second, url, false)
        assertEquals(active.id, incoming.profiles.single().id)
        val state = ProfileStoreState(profiles = listOf(active), selectedProfileId = active.id)
        val target = subscriptionRefreshTargets(state).single()
        val proposal = proposeSubscriptionRefresh(
            state,
            target,
            incoming,
            ProfileMutationOwner(7, VpnPhase.CONNECTED, true, false),
            7
        )
        assertEquals(RefreshOutcome.DEFERRED, proposal.outcome)
        assertNull(proposal.candidate)
        assertEquals(active, state.profiles.single())
    }

    @Test fun aDeletedOrChangedSourceAndANewerCommandCannotBeResurrectedByLateIo() {
        val profile = importer.importSubscription(first, url, false).profiles.single()
        val state = ProfileStoreState(profiles = listOf(profile), selectedProfileId = profile.id)
        val target = subscriptionRefreshTargets(state).single()
        val incoming = importer.importSubscription(second, url, false)
        val idle = ProfileMutationOwner(8, VpnPhase.IDLE, false, false)
        assertEquals(
            RefreshOutcome.STALE,
            proposeSubscriptionRefresh(state.copy(profiles = emptyList()), target, incoming, idle, 8).outcome
        )
        assertEquals(RefreshOutcome.STALE, proposeSubscriptionRefresh(state, target, incoming, idle, 7).outcome)
    }

    @Test fun anUnselectedSourceUpdatesWithoutChangingTheOwnedSelection() {
        val active = importer.import(first).profiles.single()
        val old = importer.importSubscription(second, url, false).profiles.single()
        val state = ProfileStoreState(profiles = listOf(active, old), selectedProfileId = active.id)
        val incoming = importer.importSubscription(second.replace("8.8.8.8:443", "9.9.9.9:443"), url, false)
        val target = SubscriptionRefreshTarget(url, listOf(old))
        val proposal = proposeSubscriptionRefresh(
            state,
            target,
            incoming,
            ProfileMutationOwner(7, VpnPhase.CONNECTED, true, false),
            7
        )
        assertEquals(RefreshOutcome.UPDATED, proposal.outcome)
        assertEquals(active, requireNotNull(proposal.candidate).profiles.single { it.id == active.id })
        assertEquals(active.id, proposal.candidate.selectedProfileId)
    }
}
