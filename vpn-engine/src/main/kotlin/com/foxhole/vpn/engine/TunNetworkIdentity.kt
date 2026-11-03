package com.foxhole.vpn.engine

import java.net.InetAddress

internal fun tunInterfaceName(fdInfo: String): String? = fdInfo.lineSequence()
    .firstOrNull { it.startsWith("iff:") }
    ?.substringAfter(':')?.trim()
    ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) }

internal fun matchesTunNetwork(
    tunInterface: String?,
    networkInterface: String?,
    expectedDns: Set<InetAddress>,
    networkDns: List<InetAddress>,
): Boolean = tunInterface != null && tunInterface == networkInterface &&
    expectedDns.isNotEmpty() && networkDns.toSet() == expectedDns
