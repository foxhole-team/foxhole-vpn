package com.foxhole.vpn.router

import com.foxhole.vpn.VpnController
import com.foxhole.core.network.ensurePublicSubscriptionUrl

internal class RouterProfileSelection(
    val phoneProfileId: String,
    val displayName: String,
    val selectedProtocol: String,
    val selectedNodeName: String,
    val selectedOptionId: String?,
    val sourceUrl: String?,
    val selectedConfiguration: String,
) {
    init {
        require(phoneProfileId.isNotBlank() && phoneProfileId.length <= 160 && phoneProfileId.none(Char::isISOControl))
        require(displayName.isNotBlank() && displayName.length <= 160 && displayName.none(Char::isISOControl))
        require(selectedNodeName.isNotBlank() && selectedNodeName.length <= 160 && selectedNodeName.none(Char::isISOControl))
        require(selectedOptionId == null || selectedOptionId.isNotBlank() && selectedOptionId.length <= 160 && selectedOptionId.none(Char::isISOControl))
        require(selectedProtocol.matches(Regex("[a-z0-9]{1,32}")))
        require(sourceUrl == null || sourceUrl.length in 1..16384 && sourceUrl.none(Char::isISOControl))
        require(selectedConfiguration.toByteArray(Charsets.UTF_8).size in 1..262144)
    }
    override fun toString(): String = "RouterProfileSelection(protocol=$selectedProtocol, subscription=${sourceUrl != null})"
}

internal class RouterPhoneProfileExport(private val controller: VpnController) {
    suspend fun selected(profileId: String): RouterProfileSelection {
        controller.awaitReady()
        val before = controller.state.value
        val profile = before.profiles.singleOrNull { it.id == profileId } ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val option = if (profile.protocolOptions.isEmpty()) null else {
            profile.protocolOptions.singleOrNull { it.id == profile.selectedProtocolOptionId }
                ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        }
        val selected = controller.exportProfile(profileId, option?.let { setOf(it.id) } ?: emptySet())
            ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val whole = controller.exportProfile(profileId) ?: throw RouterApiFailure(RouterApiError.UNSUPPORTED)
        val after = controller.state.value
        if (after.profileRevision != before.profileRevision || after.profiles.singleOrNull { it.id == profileId } != profile) {
            throw RouterApiFailure(RouterApiError.CONFLICT)
        }
        val source = whole.trim().takeIf { it.startsWith("https://", true) || it.startsWith("ssconf://", true) }
            ?.also { it.ensurePublicSubscriptionUrl(resolveHost = false) }
        val protocol = (option?.protocol ?: profile.protocol).let { if (it == "OUTLINE") "shadowsocks" else it.lowercase() }
        return RouterProfileSelection(profileId, profile.name, protocol,
            option?.name ?: profile.name, option?.id, source, selected)
    }
}
