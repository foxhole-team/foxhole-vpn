package com.foxhole.vpn.profile

internal data class SubscriptionHttpReply(
    val redirect: String? = null,
    val content: SubscriptionFetchResult? = null,
) {
    override fun toString(): String = "SubscriptionHttpReply(redirectPresent=${redirect != null}, contentPresent=${content != null})"
}
