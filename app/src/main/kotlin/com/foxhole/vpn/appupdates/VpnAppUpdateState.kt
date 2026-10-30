package com.foxhole.vpn.appupdates

import kotlinx.serialization.Serializable

@Serializable
internal data class VpnAppUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkSha256: String,
    val notes: String = "",
) {
    val isValid: Boolean get() = versionCode > 0 && versionName.length in 1..64 &&
        versionName.none(Char::isISOControl) && apkName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,190}\\.apk")) &&
        apkSha256.matches(Regex("[a-fA-F0-9]{64}")) && notes.length <= 16_384
}

@Serializable
internal data class VpnAppRelease(
    val manifest: VpnAppUpdateManifest,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseUrl: String,
)

internal enum class VpnAppUpdatePhase {
    IDLE, CHECKING, NO_RELEASE, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR, DISABLED,
}

internal enum class VpnAppUpdateError { NETWORK, RATE_LIMITED, METADATA, VERIFICATION, STORAGE, INSTALLER }

internal data class VpnAppUpdateState(
    val phase: VpnAppUpdatePhase = VpnAppUpdatePhase.IDLE,
    val release: VpnAppRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: VpnAppUpdateError? = null,
) {
    val isBusy: Boolean get() = phase in setOf(VpnAppUpdatePhase.CHECKING, VpnAppUpdatePhase.DOWNLOADING, VpnAppUpdatePhase.VERIFYING)
}

internal sealed interface VpnAppUpdateCheck {
    data object NoRelease : VpnAppUpdateCheck
    data object UpToDate : VpnAppUpdateCheck
    data object Disabled : VpnAppUpdateCheck
    data class Available(val release: VpnAppRelease) : VpnAppUpdateCheck
    data class Failed(val error: VpnAppUpdateError) : VpnAppUpdateCheck
}

internal class VpnAppUpdateFailure(val kind: VpnAppUpdateError) : Exception(kind.name)
