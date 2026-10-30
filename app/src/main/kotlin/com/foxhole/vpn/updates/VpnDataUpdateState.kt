package com.foxhole.vpn.updates

internal enum class VpnDataUpdatePhase {
    IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, UPDATED, UP_TO_DATE, ERROR,
}

internal enum class VpnDataUpdateError { NETWORK, VERIFICATION, STORAGE, NATIVE }

internal data class VpnDataUpdateState(
    val phase: VpnDataUpdatePhase = VpnDataUpdatePhase.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: VpnDataUpdateError? = null,
) {
    val isBusy: Boolean get() = phase == VpnDataUpdatePhase.CHECKING ||
        phase == VpnDataUpdatePhase.DOWNLOADING || phase == VpnDataUpdatePhase.VERIFYING
}
