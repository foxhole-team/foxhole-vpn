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
    suspend fun currentInstallGeneration(): String = "legacy"
    suspend fun readAcknowledgedRevision(generation: String): Int = readAcknowledgedRevision()
    suspend fun writeAcknowledgedRevision(generation: String, revision: Int) = writeAcknowledgedRevision(revision)
}

internal data class VpnUpdateNoticeState(
    val loaded: Boolean = false,
    val acknowledged: Boolean = false,
    val saving: Boolean = false,
    val storageError: Boolean = false,
    val retryGeneration: Int = 0,
    val installGeneration: String? = null,
)

internal class VpnUpdateNotice(private val store: VpnUpdateNoticeStore) {
    private val mutableState = MutableStateFlow(VpnUpdateNoticeState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        try {
            val generation = store.currentInstallGeneration()
            val acknowledged = store.readAcknowledgedRevision(generation) >= VPN_UPDATE_NOTICE_REVISION
            val latest = store.currentInstallGeneration()
            currentCoroutineContext().ensureActive()
            mutableState.value = VpnUpdateNoticeState(loaded = true,
                acknowledged = generation == latest && acknowledged, installGeneration = latest)
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
            val generation = current.installGeneration ?: store.currentInstallGeneration()
            val beforeWrite = store.currentInstallGeneration()
            currentCoroutineContext().ensureActive()
            if (beforeWrite != generation) {
                mutableState.value = current.copy(acknowledged = false, saving = false, storageError = false,
                    installGeneration = beforeWrite, retryGeneration = current.retryGeneration + 1)
                return@withLock
            }
            store.writeAcknowledgedRevision(generation, VPN_UPDATE_NOTICE_REVISION)
            val acknowledged = store.readAcknowledgedRevision(generation) >= VPN_UPDATE_NOTICE_REVISION
            val latest = store.currentInstallGeneration()
            currentCoroutineContext().ensureActive()
            if (latest != generation) {
                mutableState.value = current.copy(acknowledged = false, saving = false, storageError = false,
                    installGeneration = latest, retryGeneration = current.retryGeneration + 1)
                return@withLock
            }
            mutableState.value = current.copy(acknowledged = acknowledged, storageError = !acknowledged,
                installGeneration = generation,
                retryGeneration = current.retryGeneration + if (acknowledged) 0 else 1)
        } catch (cancelled: CancellationException) {
            mutableState.value = current.copy(saving = false, retryGeneration = current.retryGeneration + 1)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = current.copy(saving = false, storageError = true, retryGeneration = current.retryGeneration + 1)
        }
    }
}
