package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.MemoryRouterPairingStore
import com.foxhole.vpn.router.RouterControlPreferenceStore
import com.foxhole.vpn.router.RouterControlPreferences
import com.foxhole.vpn.router.RouterTlsIdentity

internal class RouterReviewPairingStore(private val selected: (RouterTlsIdentity) -> Unit = {}) : MemoryRouterPairingStore() {
    override suspend fun selectSaved(id: String) {
        super.selectSaved(id)
        val pairing = requireNotNull(readById(id))
        try { selected(pairing.identity) } finally { pairing.clear() }
    }
}

internal class RouterReviewPreferenceStore(private val delegate: RouterControlPreferenceStore? = null) : RouterControlPreferenceStore {
    private val lock = Any()
    private val values = mutableMapOf<String, RouterControlPreferences>()
    override suspend fun read(pairingId: String): RouterControlPreferences = delegate?.read(pairingId) ?: synchronized(lock) {
        values[pairingId] ?: RouterControlPreferences(pairingId)
    }
    override suspend fun save(preferences: RouterControlPreferences) {
        if (delegate != null) delegate.save(preferences)
        else synchronized(lock) { values[preferences.pairingId] = preferences }
    }
}
