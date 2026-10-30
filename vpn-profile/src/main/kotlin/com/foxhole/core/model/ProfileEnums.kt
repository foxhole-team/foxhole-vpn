package com.foxhole.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProfileSourceType {
    SUBSCRIPTION_URL,
    SHARE_URI,
    RAW_CONFIG_JSON,
    RAW_WIREGUARD_TEXT,
}

@Serializable
enum class ProtocolHint {
    VLESS,
    TROJAN,
    SHADOWSOCKS,
    WIREGUARD,
    HYSTERIA2,
    VMESS,
    OUTLINE,
    NAIVE,
    TUIC,
    ANYTLS,
    TOR,
    LOCAL_GUARD,
    CUSTOM_CONFIG,
    UNKNOWN,
}
