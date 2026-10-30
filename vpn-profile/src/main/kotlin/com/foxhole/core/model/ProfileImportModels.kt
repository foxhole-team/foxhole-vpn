package com.foxhole.core.model

import kotlinx.serialization.Serializable

data class ParsedImport(
    val sourceType: ProfileSourceType,
    val protocolHint: ProtocolHint,
    val displayName: String,
    val normalizedConfigJson: String?,
    val sourceUrl: String? = null,
    val nodesCount: Int = 1,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val entryReports: List<SubscriptionEntryReport> = emptyList(),
)

data class ParsedSubscriptionProfile(
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
)

enum class SubscriptionEntryStatus {
    ACCEPTED,
    IGNORED_UNSUPPORTED,
}

data class SubscriptionEntryReport(
    val protocolLabel: String,
    val protocolHint: ProtocolHint,
    val status: SubscriptionEntryStatus,
    val sourceLine: Int,
    val reason: String? = null,
)

data class ParsedSubscriptionImport(
    val displayName: String,
    val profiles: List<ParsedSubscriptionProfile>,
    val subscriptionExpiresAt: Long? = null,
    val entryReports: List<SubscriptionEntryReport> = emptyList(),
) {
    init {
        require(profiles.isNotEmpty()) { "subscription must contain at least one profile" }
    }

    val nodesCount: Int
        get() = profiles.size
}

@Serializable
data class StoredProfileSecret(
    val rawInput: String? = null,
    val subscriptionUrl: String? = null,
    val resolvedConfigJson: String? = null,
    val subscriptionExpiresAt: Long? = null,
    val protocolOptions: List<StoredProfileProtocolOption> = emptyList(),
    val selectedProtocolOptionId: String? = null,
    val requiresInsecureTls: Boolean = false,
    val insecureTlsConsentGranted: Boolean? = null,
)

@Serializable
data class StoredProfileProtocolOption(
    val id: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val normalizedConfigJson: String,
    val requiresInsecureTls: Boolean = false,

    val enabled: Boolean = true,
)
