package com.foxhole.vpn.engine

internal data class EngineNetworkCandidate(
    val handle: Long,
    val vpn: Boolean,
    val internet: Boolean,
    val unrestricted: Boolean,
    val validated: Boolean,
    val wifi: Boolean,
    val ethernet: Boolean,
    val unmetered: Boolean,
    val cellular: Boolean,
    val ownerUid: Int?,
)
