package com.foxhole.vpn.appupdates

import java.security.MessageDigest

internal data class VpnUpdateResumeTicket(
    val profileId: String,
    val configurationDigest: String,
    val targetVersionCode: Long,
    val createdAtMillis: Long,
)

internal fun vpnUpdateResumeAllowed(
    ticket: VpnUpdateResumeTicket,
    installedVersionCode: Long,
    profileId: String?,
    configurationDigest: String?,
    nowMillis: Long,
): Boolean = ticket.profileId.isNotBlank() && ticket.profileId == profileId &&
    ticket.configurationDigest.length == 64 && ticket.configurationDigest == configurationDigest &&
    ticket.targetVersionCode > 0 && ticket.targetVersionCode == installedVersionCode && ticket.createdAtMillis > 0 &&
    nowMillis >= ticket.createdAtMillis && nowMillis - ticket.createdAtMillis <= 60 * 60 * 1000L

internal fun vpnUpdateConfigurationDigest(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)).hexDigest()
