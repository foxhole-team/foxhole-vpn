package com.foxhole.vpn.review

import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnProtocolChangeRequest
import com.foxhole.vpn.VpnProtocolSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.protocolChangeCurrent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Review-only in-memory screen state. No engine, storage, resolver or network client. */
internal class ReviewVpnController : VpnController {
    private val sequence = AtomicLong()
    private val mutableSettings = MutableStateFlow(
        VpnSettings(autoRefreshSubscriptions = false, autoCheckConnectionData = false),
    )
    private val mutableState = MutableStateFlow(
        VpnUiState(
            isReady = true,
            profiles = demoProfiles(),
            selectedProfileId = HOME_PROFILE,
            deviceLocation = VpnDeviceLocation(
                location = demoDeviceLocation(),
                status = DeviceLocationStatus.CURRENT,
            ),
        ),
    )

    override val state: StateFlow<VpnUiState> = mutableState
    override val settings: StateFlow<VpnSettings> = mutableSettings

    override suspend fun awaitReady() = Unit

    override fun connectSelected() {
        val connection = sequence.incrementAndGet()
        mutableState.update { current ->
            if (current.selectedProfile == null) current else current.copy(
                phase = VpnPhase.CONNECTED,
                location = demoServerLocation(current.selectedProfileId),
                hasOwnedTunnel = true,
                hasPendingStart = false,
                connectionId = connection,
                message = null,
            )
        }
    }

    override fun disconnect() {
        mutableState.update { current ->
            current.copy(
                phase = VpnPhase.IDLE,
                location = null,
                hasOwnedTunnel = false,
                hasPendingStart = false,
                connectionId = null,
                message = null,
            )
        }
    }

    override fun selectProfile(id: String) {
        mutableState.update { current ->
            if (current.canDisconnect || current.profiles.none { it.id == id }) current else current.copy(
                selectedProfileId = id,
                profileRevision = current.profileRevision + 1,
            )
        }
    }

    override suspend fun selectProtocol(profileId: String, optionId: String) {
        mutableState.update { current ->
            val profile = current.profiles.firstOrNull { it.id == profileId }
            val option = profile?.protocolOptions?.firstOrNull { it.id == optionId }
            if (profile == null || option == null) current else current.copy(
                profiles = current.profiles.map {
                    if (it.id == profileId) it.copy(
                        protocol = option.protocol,
                        selectedProtocolOptionId = option.id,
                    ) else it
                },
                profileRevision = current.profileRevision + 1,
            )
        }
    }

    override suspend fun confirmProtocolChange(request: VpnProtocolChangeRequest) {
        if (!protocolChangeCurrent(request, state.value)) return
        selectProtocol(request.profileId, request.optionId)
        connectSelected()
    }

    override suspend fun exportProfile(profileId: String, optionIds: Set<String>?): String? {
        val profile = state.value.profiles.firstOrNull { it.id == profileId } ?: return null
        val option = optionIds?.firstOrNull()?.let { id -> profile.protocolOptions.firstOrNull { it.id == id } }
        val protocol = option?.protocol ?: profile.protocol
        return if (protocol == "HYSTERIA2") DEMO_HY2 else DEMO_GRPC
    }

    override fun refreshLocation() {
        mutableState.update { current ->
            current.copy(deviceLocation = VpnDeviceLocation(demoDeviceLocation(), DeviceLocationStatus.CURRENT))
        }
    }

    override fun claimConnectionCue(connectionId: Long): Boolean = false

    override suspend fun importProfiles(text: String, allowInsecureTls: Boolean): VpnImportResult {
        // Explicit paste/manual input is discarded; it is never parsed, fetched, stored or echoed.
        val id = "demo-import-${sequence.incrementAndGet()}"
        mutableState.update { current ->
            current.copy(
                profiles = current.profiles + demoHomeProfile(id, "Demo imported · HY2"),
                selectedProfileId = current.selectedProfileId ?: id,
                profileRevision = current.profileRevision + 1,
            )
        }
        return VpnImportResult.Success(added = 1)
    }

    override suspend fun refreshSubscription(profileId: String): VpnImportResult =
        VpnImportResult.Success(added = 0, updated = if (state.value.profiles.any { it.id == profileId }) 1 else 0)

    override suspend fun removeProfile(id: String) {
        mutableState.update { current ->
            if (current.canDisconnect) current else {
                val remaining = current.profiles.filterNot { it.id == id }
                current.copy(
                    profiles = remaining,
                    selectedProfileId = current.selectedProfileId.takeIf { selected -> remaining.any { it.id == selected } }
                        ?: remaining.firstOrNull()?.id,
                    profileRevision = current.profileRevision + 1,
                )
            }
        }
    }

    override suspend fun updateSettings(settings: VpnSettings) {
        mutableSettings.value = settings
    }

    internal companion object {
        const val DEMO_HY2 = "hysteria2://DemoOnly@demo-router.invalid:443/?sni=demo-router.invalid#Demo%20HY2"
        private const val DEMO_GRPC = "vless://11111111-1111-4111-8111-111111111111@demo-node.invalid:443" +
            "?encryption=none&security=tls&type=grpc&serviceName=Demo&mode=gun#Demo%20gRPC"
        private const val HOME_PROFILE = "demo-home"

        private fun demoHomeProfile(id: String, name: String) = VpnProfileSummary(
            id = id,
            name = name,
            protocol = "HYSTERIA2",
            protocolOptions = listOf(VpnProtocolSummary("demo-hy2", "Demo HY2", "HYSTERIA2")),
            selectedProtocolOptionId = "demo-hy2",
        )

        private fun demoProfiles() = listOf(
            demoHomeProfile(HOME_PROFILE, "Demo Home · HY2"),
            VpnProfileSummary(
                id = "demo-tokyo",
                name = "Demo Tokyo · gRPC",
                protocol = "VLESS",
                protocolOptions = listOf(
                    VpnProtocolSummary("demo-vless", "Demo VLESS gRPC", "VLESS"),
                    VpnProtocolSummary("demo-hy2", "Demo HY2", "HYSTERIA2"),
                ),
                selectedProtocolOptionId = "demo-vless",
            ),
        )

        private fun demoDeviceLocation() = VpnLocation(
            "DE", "Demo Berlin", "Demo Germany", "192.0.2.10", 52.52, 13.405,
        )

        private fun demoServerLocation(profileId: String?) = if (profileId == "demo-tokyo") {
            VpnLocation("JP", "Demo Tokyo", "Demo Japan", "203.0.113.10", 35.6762, 139.6503)
        } else {
            VpnLocation("DE", "Demo Berlin", "Demo Germany", "203.0.113.20", 52.52, 13.405)
        }
    }
}
