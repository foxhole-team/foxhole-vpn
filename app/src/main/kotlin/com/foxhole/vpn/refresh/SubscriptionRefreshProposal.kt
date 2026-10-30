package com.foxhole.vpn.refresh

import com.foxhole.vpn.ProfileMutationOwner
import com.foxhole.vpn.importRequiresDisconnect
import com.foxhole.vpn.profile.ImportedVpnProfiles
import com.foxhole.vpn.profile.VpnProfile
import com.foxhole.vpn.profile.withSelectedProtocol
import com.foxhole.vpn.storage.ProfileStoreState

internal data class SubscriptionRefreshProposal(val outcome: RefreshOutcome, val candidate: ProfileStoreState? = null) {
    override fun toString(): String = "SubscriptionRefreshProposal(outcome=$outcome, candidate=<redacted>)"
}

internal fun subscriptionRefreshTargets(state: ProfileStoreState): List<SubscriptionRefreshTarget> =
    state.profiles.filter { it.sourceUrl != null }.groupBy { requireNotNull(it.sourceUrl) }
        .map { (source, profiles) -> SubscriptionRefreshTarget(source, profiles) }

internal fun proposeSubscriptionRefresh(
    current: ProfileStoreState,
    target: SubscriptionRefreshTarget,
    incoming: ImportedVpnProfiles,
    owner: ProfileMutationOwner,
    expectedGeneration: Long,
    ownedProfileId: String? = null,
): SubscriptionRefreshProposal {
    if (owner.generation != expectedGeneration || current.profiles.filter {
            it.sourceUrl == target.source
        } != target.profiles
    ) {
        return SubscriptionRefreshProposal(RefreshOutcome.STALE)
    }
    if (incoming.profiles.isEmpty() || incoming.profiles.any { it.sourceUrl != target.source }) {
        return SubscriptionRefreshProposal(RefreshOutcome.FAILED)
    }
    val replacements = incoming.profiles.map { profile ->
        retainSelectionAndConsent(profile, target.profiles.firstOrNull { it.id == profile.id }, target.allowInsecureTls)
    }
    val insertion = current.profiles.indexOfFirst { it.sourceUrl == target.source }
    val profiles = current.profiles.filterNot { it.sourceUrl == target.source }.toMutableList().apply {
        addAll(current.profiles.take(insertion).count { it.sourceUrl != target.source }, replacements)
    }
    if (profiles.size > 256 || profiles.map { it.id }.distinct().size != profiles.size) {
        return SubscriptionRefreshProposal(RefreshOutcome.FAILED)
    }
    val selected = current.selectedProfileId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id
    val candidate = current.copy(profiles = profiles, selectedProfileId = selected)
    if (importRequiresDisconnect(current, candidate, owner) || (
            ownedProfileId != null &&
                current.profiles.firstOrNull { it.id == ownedProfileId }?.normalizedConfigJson !=
                candidate.profiles.firstOrNull { it.id == ownedProfileId }?.normalizedConfigJson
            )
    ) {
        return SubscriptionRefreshProposal(RefreshOutcome.DEFERRED)
    }
    return SubscriptionRefreshProposal(
        if (candidate.profiles == current.profiles && selected == current.selectedProfileId) {
            RefreshOutcome.UNCHANGED
        } else {
            RefreshOutcome.UPDATED
        },
        candidate
    )
}

private fun retainSelectionAndConsent(profile: VpnProfile, existing: VpnProfile?, groupConsent: Boolean): VpnProfile {
    val previousOption = existing?.selectedProtocolOptionId
        ?: existing?.protocolOptions?.firstOrNull { it.normalizedConfigJson == existing.normalizedConfigJson }?.id
    val option = previousOption?.takeIf { id -> profile.protocolOptions.any { it.id == id } }
        ?: profile.protocolOptions.firstOrNull { it.normalizedConfigJson == existing?.normalizedConfigJson }?.id
    return (if (option != null) profile.withSelectedProtocol(option) else profile).copy(
        insecureTlsConsent = existing?.insecureTlsConsent ?: groupConsent,
    )
}
