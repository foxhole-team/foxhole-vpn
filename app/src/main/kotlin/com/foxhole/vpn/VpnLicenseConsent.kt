package com.foxhole.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val VPN_LICENSE_AGREEMENT_VERSION = 1

internal interface VpnLicenseStore {
    suspend fun readAcceptedVersion(): Int
    suspend fun writeAcceptedVersion(version: Int)
}

internal data class VpnLicenseState(
    val loaded: Boolean = false,
    val accepted: Boolean = false,
    val saving: Boolean = false,
    val storageError: Boolean = false,
)

internal class VpnLicenseConsent(private val store: VpnLicenseStore) {
    private val mutableState = MutableStateFlow(VpnLicenseState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        try {
            val accepted = store.readAcceptedVersion() == VPN_LICENSE_AGREEMENT_VERSION
            currentCoroutineContext().ensureActive()
            mutableState.value = VpnLicenseState(loaded = true, accepted = accepted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = VpnLicenseState(loaded = true, storageError = true)
        }
    }

    suspend fun accept(confirmed: Boolean) = mutex.withLock {
        val current = mutableState.value
        if (!confirmed || !current.loaded || current.accepted || current.saving) return@withLock
        mutableState.value = current.copy(saving = true, storageError = false)
        try {
            currentCoroutineContext().ensureActive()
            store.writeAcceptedVersion(VPN_LICENSE_AGREEMENT_VERSION)
            val accepted = store.readAcceptedVersion() == VPN_LICENSE_AGREEMENT_VERSION
            currentCoroutineContext().ensureActive()
            mutableState.value = current.copy(accepted = accepted, storageError = !accepted)
        } catch (cancelled: CancellationException) {
            mutableState.value = current.copy(saving = false)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = current.copy(saving = false, storageError = true)
        }
    }
}
