package com.foxhole.vpn.ui

import java.util.Locale

private val generatedProtocolOptionName = Regex("(vpn-core|foxhole)-(.+)", RegexOption.IGNORE_CASE)

internal fun vpnProtocolOptionDisplayName(name: String, protocolHint: String = ""): String {
    val match = generatedProtocolOptionName.matchEntire(name) ?: return name
    val token = match.groupValues[2]
    val protocol = generatedProtocolDisplayName(token) ?: token.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.let { generatedProtocolDisplayName(protocolHint) } ?: token.substringAfterLast('-')
        .takeIf { suffix -> suffix.isNotEmpty() && suffix.all { it in '0'..'9' } }
        ?.let { generatedProtocolDisplayName(token.substringBeforeLast('-')) } ?: return name
    val family = if (match.groupValues[1].equals("vpn-core", ignoreCase = true)) "VPN Core" else "FoxHole"
    return "$family $protocol"
}

private fun generatedProtocolDisplayName(token: String): String? = when (token.lowercase(Locale.ROOT)) {
    "vless" -> protocolDisplayName("vless")
    "vmess" -> "VMess"
    "trojan" -> protocolDisplayName("trojan")
    "ss", "shadowsocks" -> protocolDisplayName("shadowsocks")
    "ss2022", "ss-2022", "shadowsocks2022", "shadowsocks-2022" -> "Shadowsocks 2022"
    "outline" -> protocolDisplayName("outline")
    "wg", "wireguard" -> protocolDisplayName("wireguard")
    "awg", "amneziawg" -> "AmneziaWG"
    "hy2", "hysteria2", "hysteria-2" -> protocolDisplayName("hysteria2")
    "naive", "naiveproxy" -> protocolDisplayName("naive")
    "tuic" -> protocolDisplayName("tuic")
    "anytls" -> protocolDisplayName("anytls")
    else -> null
}
