package com.foxhole.core.runtime

enum class FoxCoreConfigRejection {
    PREPARED_CONFIG_MISSING,
    MALFORMED_JSON,
    PROFILE_KIND_UNSUPPORTED,
    PROTOCOL_UNSUPPORTED,
    PROTOCOL_MISMATCH,
    INVALID_SHAPE,
    UNSUPPORTED_FIELD,
    UNSUPPORTED_TRANSPORT,
    UNSUPPORTED_SECURITY,
    EFFECTFUL_CONFIG_UNSUPPORTED,
    OVERLAY_UNAVAILABLE,
    OVERLAY_CONFIGURATION_UNSUPPORTED,
    POLICY_UNREPRESENTABLE,

    PACKET_TUNNEL_DNS_MISSING,
}

class FoxCoreConfigTranslationException internal constructor(
    val rejection: FoxCoreConfigRejection,
    val path: String,

    val explanation: String? = null,
) : IllegalArgumentException(
    "${rejection.name.lowercase()} at $path" + explanation?.let { ": $it" }.orEmpty(),
)
