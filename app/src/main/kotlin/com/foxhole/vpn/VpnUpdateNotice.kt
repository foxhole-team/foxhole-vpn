package com.foxhole.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val VPN_UPDATE_NOTICE_REVISION = 1

internal interface VpnUpdateNoticeStore {
    suspend fun readAcknowledgedRevision(): Int
    suspend fun writeAcknowledgedRevision(revision: Int)
}

internal data class VpnUpdateNoticeState(
    val loaded: Boolean = false,
    val acknowledged: Boolean = false,
    val saving: Boolean = false,
    val storageError: Boolean = false,
    val retryGeneration: Int = 0,
)

internal class VpnUpdateNotice(private val store: VpnUpdateNoticeStore) {
    private val mutableState = MutableStateFlow(VpnUpdateNoticeState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        try {
            val acknowledged = store.readAcknowledgedRevision() >= VPN_UPDATE_NOTICE_REVISION
            currentCoroutineContext().ensureActive()
            mutableState.value = VpnUpdateNoticeState(loaded = true, acknowledged = acknowledged)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = VpnUpdateNoticeState(loaded = true, storageError = true)
        }
    }

    suspend fun acknowledge() = mutex.withLock {
        val current = mutableState.value
        if (!current.loaded || current.acknowledged || current.saving) return@withLock
        mutableState.value = current.copy(saving = true, storageError = false)
        try {
            currentCoroutineContext().ensureActive()
            store.writeAcknowledgedRevision(VPN_UPDATE_NOTICE_REVISION)
            val acknowledged = store.readAcknowledgedRevision() >= VPN_UPDATE_NOTICE_REVISION
            currentCoroutineContext().ensureActive()
            mutableState.value = current.copy(acknowledged = acknowledged, storageError = !acknowledged,
                retryGeneration = current.retryGeneration + if (acknowledged) 0 else 1)
        } catch (cancelled: CancellationException) {
            mutableState.value = current.copy(saving = false, retryGeneration = current.retryGeneration + 1)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = current.copy(saving = false, storageError = true, retryGeneration = current.retryGeneration + 1)
        }
    }
}
