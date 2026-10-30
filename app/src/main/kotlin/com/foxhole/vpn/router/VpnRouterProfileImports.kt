package com.foxhole.vpn.router

import com.foxhole.vpn.DefaultVpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.profile.VpnProfileImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class VpnRouterProfileImports(private val controller: DefaultVpnController,
    private val pairings: EncryptedRouterPairingStore) {
    suspend fun authenticated(value: RouterAuthenticatedProfileImport): Boolean {
        val saved = pairings.read() ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        try {
            if (saved.id != value.marker.pairingId || saved.identity != value.identity || !saved.active ||
                !value.marker.matches(value.sourceUri, saved.id)) throw RouterApiFailure(RouterApiError.CONFLICT)
        } finally { saved.clear() }
        val digest = withContext(Dispatchers.IO) {
            val profile = VpnProfileImporter().import(value.content, allowInsecureTls = false).profiles.singleOrNull()
                ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
            routerProfileDigest(profile.normalizedConfigJson)
        }
        val result = controller.importProfiles(value.content, allowInsecureTls = false)
        if (result !is VpnImportResult.Success || result.rejected != 0 || result.added + result.updated == 0) return false
        val reference = controller.routerImportedProfileReference(digest) ?: throw RouterApiFailure(RouterApiError.CONFLICT)
        pairings.trustProfile(value.identity, RouterTrustedProfile(reference.first, reference.second,
            value.identity.certificateSha256, value.marker))
        return controller.routerImportedProfileReference(digest) == reference
    }
}
