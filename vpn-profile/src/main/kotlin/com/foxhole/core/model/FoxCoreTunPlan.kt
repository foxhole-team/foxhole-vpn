package com.foxhole.core.model

import kotlinx.serialization.Serializable

data class FoxCoreTunPlan(
    val mtu: Int,
    val ipv4Address: String,
    val ipv4PrefixLength: Int,
    val ipv6Address: String?,
    val ipv6PrefixLength: Int?,
    val routes: List<FoxCoreTunRoute>,
    val advertisedDnsServers: List<String>,
    val allowedApplications: List<String> = emptyList(),
    val disallowedApplications: List<String> = emptyList(),
)

data class FoxCoreTunRoute(
    val address: String,
    val prefixLength: Int,
)
