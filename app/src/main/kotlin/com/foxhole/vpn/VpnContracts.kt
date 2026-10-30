package com.foxhole.vpn

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class VpnPhase { IDLE, CONNECTING, VALIDATING, CONNECTED, STOPPING, ERROR }

@Serializable
enum class VpnTheme { SYSTEM, DARK, LIGHT, NIGHT }

@Serializable
enum class VpnLanguage { SYSTEM, EN, RU, JA, DE, FR, ES, ZH }

@Serializable
enum class VpnFontStyle { PIXEL, STANDARD }

// Kept for strict decoding of existing encrypted profile stores.
@Serializable
enum class VpnGlobeStyle { VOLUME, VECTOR }

@Serializable
data class VpnSettings(
    val theme: VpnTheme = VpnTheme.SYSTEM,
    val language: VpnLanguage = VpnLanguage.SYSTEM,
    val globeStyle: VpnGlobeStyle = VpnGlobeStyle.VOLUME,
    val autoRefreshSubscriptions: Boolean = true,
    val subscriptionRefreshIntervalHours: Int = 12,
    val autoCheckConnectionData: Boolean = true,
    val connectionDataCheckIntervalHours: Int = 24,
    val fontStyle: VpnFontStyle = VpnFontStyle.PIXEL,
)

data class VpnProfileSummary(
    val id: String,
    val name: String,
    val protocol: String,
    val refreshable: Boolean = false,
    val protocolOptions: List<VpnProtocolSummary> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val subscriptionExpiresAt: Long? = null,
)

data class VpnProtocolSummary(val id: String, val name: String, val protocol: String)

data class VpnProtocolChangeRequest(
    val profileId: String,
    val optionId: String,
    val previousOptionId: String?,
    val connectionId: Long,
    val profileRevision: Long,
)

data class VpnLocation(
    val countryCode: String,
    val city: String,
    val country: String,
    val ip: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

enum class DeviceLocationStatus { UNKNOWN, LOADING, CURRENT, CACHED, UNAVAILABLE }

data class VpnDeviceLocation(
    val location: VpnLocation? = null,
    val status: DeviceLocationStatus = DeviceLocationStatus.UNKNOWN,
    val checkedAtElapsedRealtimeMs: Long? = null,
)

data class VpnUiState(
    val isReady: Boolean = false,
    val profiles: List<VpnProfileSummary> = emptyList(),
    val selectedProfileId: String? = null,
    val phase: VpnPhase = VpnPhase.IDLE,
    val location: VpnLocation? = null,
    val message: String? = null,
    val hasOwnedTunnel: Boolean = false,
    val hasPendingStart: Boolean = false,
    val canRestartDuringStop: Boolean = false,
    val connectionId: Long? = null,
    val deviceLocation: VpnDeviceLocation = VpnDeviceLocation(),
    val profileRevision: Long = 0,
) {
    val selectedProfile: VpnProfileSummary?
        get() = profiles.firstOrNull { it.id == selectedProfileId }

    val canDisconnect: Boolean get() = hasOwnedTunnel || isBusy
    val isConnected: Boolean get() = phase == VpnPhase.CONNECTED
    val isBusy: Boolean get() = phase in setOf(VpnPhase.CONNECTING, VpnPhase.VALIDATING, VpnPhase.STOPPING)
}

sealed interface VpnImportResult {
    data class Success(val added: Int, val updated: Int = 0, val rejected: Int = 0) : VpnImportResult
    data object NeedsInsecureTlsConsent : VpnImportResult
    data class Failure(val message: String) : VpnImportResult
}

interface VpnController {
    val state: StateFlow<VpnUiState>
    val settings: StateFlow<VpnSettings>

    suspend fun awaitReady()

    fun connectSelected()
    fun disconnect()
    fun selectProfile(id: String)
    suspend fun selectProtocol(profileId: String, optionId: String) {}
    suspend fun confirmProtocolChange(request: VpnProtocolChangeRequest) {}
    suspend fun exportProfile(profileId: String, optionIds: Set<String>? = null): String? = null
    fun refreshLocation()
    fun claimConnectionCue(connectionId: Long): Boolean = true
    suspend fun importProfiles(text: String, allowInsecureTls: Boolean = false): VpnImportResult
    suspend fun refreshSubscription(profileId: String): VpnImportResult
    suspend fun removeProfile(id: String)
    suspend fun removeProfile(id: String, expectedProfileRevision: Long) {
        val current = state.value
        if (current.profileRevision == expectedProfileRevision && !current.canDisconnect && !current.isConnected) removeProfile(id)
    }
    suspend fun updateSettings(settings: VpnSettings)
    val appLanguageSettingsReady: Boolean get() = state.value.isReady
    suspend fun updateAppLanguage(language: VpnLanguage): Boolean {
        awaitReady()
        if (!appLanguageSettingsReady) return false
        updateSettings(settings.value.copy(language = language))
        return appLanguageSettingsReady && settings.value.language == language
    }
}
