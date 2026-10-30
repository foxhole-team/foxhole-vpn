package com.foxhole.vpn.profile

import com.foxhole.core.model.StoredProfileProtocolOption
import kotlinx.serialization.Serializable

@Serializable
data class VpnProfile(
    val id: String,
    val name: String,
    val protocol: String,
    val normalizedConfigJson: String,
    val sourceUrl: String? = null,
    val sourceIdentity: String? = null,
    val insecureTlsConsent: Boolean = false,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val subscriptionExpiresAt: Long? = null,
) {
    override fun toString(): String = "VpnProfile(id=$id, protocol=$protocol, credentials=<redacted>)"
}

class InsecureTlsConsentRequired : IllegalArgumentException("insecure_tls_consent_required")

class ProfileImportFailure : IllegalArgumentException("profile_import_failed")
