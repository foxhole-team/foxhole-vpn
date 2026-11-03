package com.foxhole.vpn.engine

internal fun preferredUnderlyingNetwork(defaultHandle: Long?, candidates: List<EngineNetworkCandidate>): Long? {
    val eligible = candidates.filter { !it.vpn && it.internet && it.unrestricted }
    eligible.firstOrNull { it.handle == defaultHandle }?.let { return it.handle }
    return eligible.maxByOrNull(::underlyingPreference)?.handle
}

internal fun isOwnedVpnNetwork(candidate: EngineNetworkCandidate, ownUid: Int, sdk: Int): Boolean =
    candidate.vpn && (sdk < 30 || candidate.ownerUid == ownUid)

internal fun ownedVpnNetwork(
    candidates: List<EngineNetworkCandidate>,
    ownUid: Int,
    sdk: Int,
    previousHandles: Set<Long>,
    freshOwnedTun: Boolean = false,
): Long? {
    val owned = candidates.filter { isOwnedVpnNetwork(it, ownUid, sdk) }.distinctBy { it.handle }
    owned.firstOrNull { it.handle !in previousHandles }?.let { return it.handle }
    // On old APIs, agent reuse requires a freshly established, generation-owned TUN handover.
    return if (sdk >= 30 || freshOwnedTun) owned.singleOrNull()?.handle else null
}

private fun underlyingPreference(candidate: EngineNetworkCandidate): Int =
    (if (candidate.validated) 100 else 0) +
        (if (candidate.wifi) 50 else 0) +
        (if (candidate.ethernet) 45 else 0) +
        (if (candidate.unmetered) 25 else 0) +
        (if (candidate.cellular) 10 else 0)
