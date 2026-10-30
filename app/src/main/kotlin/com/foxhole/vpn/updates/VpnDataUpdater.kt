package com.foxhole.vpn.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

internal class VpnDataUpdater(
    directory: File,
    currentVersion: String,
    private val installTables: (ByteArray) -> Int,
    private val clearTables: () -> Unit,
    private val source: FingerprintUpdateSource = FingerprintHttpSource(),
    private val verifier: FingerprintFeedVerifier = FingerprintFeedVerifier(currentVersion),
    private val cache: FingerprintUpdateCache = FileFingerprintUpdateCache(File(directory, "core-fingerprint-tables.v1")),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
    private val baselineDigests: Map<String, String> = FingerprintBaseline.digests,
    checkCache: FingerprintUpdateCache? = null,
) {
    private val mutable = MutableStateFlow(VpnDataUpdateState())
    val state: StateFlow<VpnDataUpdateState> = mutable
    private val lock = Mutex()
    private var installed: VerifiedFingerprintBundle? = null
    private val checkedCache = checkCache ?: if (cache is FileFingerprintUpdateCache) {
        FileFingerprintUpdateCache(File(directory, "checked-core-fingerprint-tables.v1"))
    } else MemoryCheckedCache()
    @Volatile var hasVerifiedCheckCache = false
        private set
    @Volatile private var generation = 0L

    suspend fun restore() = operation(VpnDataUpdatePhase.IDLE) {
        try {
            val stored = stage(VpnDataUpdateError.STORAGE) { cache.read() }
            if (stored != null) {
                // Accepted installed tables stay usable offline; checked candidates must remain fresh.
                val verified = stage(VpnDataUpdateError.VERIFICATION) { verifier.verify(stored, now(), fresh = false) }
                currentCoroutineContext().ensureActive()
                admit(verified)
                installed = verified
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DataUpdateFailure) {
            if (installed == null) stage(VpnDataUpdateError.NATIVE) { clearTables() }
            throw failure
        }
        restoreCheckedCandidate()
    }

    private fun restoreCheckedCandidate() {
        hasVerifiedCheckCache = false
        try {
            val raw = stage(VpnDataUpdateError.STORAGE) { checkedCache.read() } ?: return
            val verified = stage(VpnDataUpdateError.VERIFICATION) {
                verifier.verify(raw, now()).also { it.manifest.requireNotOlderThan(installed?.manifest) }
            }
            hasVerifiedCheckCache = true
            mutable.value = VpnDataUpdateState(if (changesEffectiveTables(verified)) VpnDataUpdatePhase.AVAILABLE else VpnDataUpdatePhase.UP_TO_DATE)
        } catch (failure: DataUpdateFailure) {
            try { stage(VpnDataUpdateError.STORAGE) { checkedCache.remove() } }
            catch (removal: DataUpdateFailure) { failure.addSuppressed(removal) }
            mutable.value = VpnDataUpdateState(VpnDataUpdatePhase.ERROR, error = failure.kind)
        }
    }

    suspend fun check() = operation(VpnDataUpdatePhase.CHECKING) {
        val (raw, manifest) = fetchManifest()
        if (installed?.manifest == manifest) {
            stage(VpnDataUpdateError.STORAGE) { checkedCache.write(requireNotNull(installed).bundle) }
            currentCoroutineContext().ensureActive()
            hasVerifiedCheckCache = true
            mutable.value = VpnDataUpdateState(VpnDataUpdatePhase.UP_TO_DATE)
            return@operation
        }
        val document = stage(VpnDataUpdateError.NETWORK) { source.document(manifest.size) {} }
        val verified = stage(VpnDataUpdateError.VERIFICATION) {
            verifier.verify(FingerprintBundle(raw.manifest, raw.signature, document), now())
        }
        currentCoroutineContext().ensureActive()
        stage(VpnDataUpdateError.STORAGE) { checkedCache.write(verified.bundle) }
        currentCoroutineContext().ensureActive()
        hasVerifiedCheckCache = true
        mutable.value = VpnDataUpdateState(if (changesEffectiveTables(verified)) VpnDataUpdatePhase.AVAILABLE else VpnDataUpdatePhase.UP_TO_DATE)
    }

    suspend fun update() = operation(VpnDataUpdatePhase.DOWNLOADING) { ticket ->
        val (raw, manifest) = fetchManifest()
        mutable.value = VpnDataUpdateState(VpnDataUpdatePhase.DOWNLOADING, totalBytes = manifest.size)
        val context = currentCoroutineContext()
        val document = stage(VpnDataUpdateError.NETWORK) {
            source.document(manifest.size) { received ->
                mutable.update { current ->
                    if (generation == ticket && context.isActive && current.phase == VpnDataUpdatePhase.DOWNLOADING) {
                        VpnDataUpdateState(VpnDataUpdatePhase.DOWNLOADING, received, manifest.size)
                    } else current
                }
            }
        }
        mutable.value = VpnDataUpdateState(VpnDataUpdatePhase.VERIFYING, manifest.size, manifest.size)
        val verified = stage(VpnDataUpdateError.VERIFICATION) {
            verifier.verify(FingerprintBundle(raw.manifest, raw.signature, document), now())
        }
        val changed = changesEffectiveTables(verified)
        currentCoroutineContext().ensureActive()
        stage(VpnDataUpdateError.STORAGE) { checkedCache.write(verified.bundle) }
        currentCoroutineContext().ensureActive()
        hasVerifiedCheckCache = true
        val previous = installed
        var admitted = false
        var persisted = false
        try {
            admit(verified)
            admitted = true
            currentCoroutineContext().ensureActive()
            stage(VpnDataUpdateError.STORAGE) { cache.write(verified.bundle) }
            persisted = true
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    if (admitted) rollback(previous, persisted)
                } catch (failure: DataUpdateFailure) {
                    mutable.value = VpnDataUpdateState(VpnDataUpdatePhase.ERROR, error = failure.kind)
                    cancelled.addSuppressed(failure)
                }
            }
            throw cancelled
        } catch (failure: DataUpdateFailure) {
            if (admitted) rollback(previous, persisted)
            throw failure
        }
        installed = verified
        mutable.value = VpnDataUpdateState(if (changed) VpnDataUpdatePhase.UPDATED else VpnDataUpdatePhase.UP_TO_DATE)
    }

    private suspend fun fetchManifest(): Pair<FingerprintManifestBytes, FingerprintManifest> {
        val raw = stage(VpnDataUpdateError.NETWORK) { source.manifest() }
        val manifest = stage(VpnDataUpdateError.VERIFICATION) { verifier.manifest(raw.manifest, raw.signature, now()) }
        stage(VpnDataUpdateError.VERIFICATION) { manifest.requireNotOlderThan(installed?.manifest) }
        return raw to manifest
    }

    private fun changesEffectiveTables(candidate: VerifiedFingerprintBundle): Boolean =
        baselineDigests + installed?.profileDigests.orEmpty() != baselineDigests + candidate.profileDigests

    private fun admit(verified: VerifiedFingerprintBundle) {
        stage(VpnDataUpdateError.NATIVE) { require(installTables(verified.bundle.document) > 0) { "fingerprint native admission refused" } }
    }

    private fun rollback(previous: VerifiedFingerprintBundle?, persisted: Boolean) {
        var failure: DataUpdateFailure? = null
        try {
            if (previous != null) admit(previous) else stage(VpnDataUpdateError.NATIVE) { clearTables() }
        } catch (error: DataUpdateFailure) { failure = error }
        if (persisted) {
            try {
                stage(VpnDataUpdateError.STORAGE) {
                    if (previous != null) cache.write(previous.bundle) else cache.remove()
                }
            } catch (error: DataUpdateFailure) {
                failure?.addSuppressed(error)
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }

    private suspend fun operation(initial: VpnDataUpdatePhase, action: suspend (Long) -> Unit) = withContext(io) {
        lock.withLock {
            val previousState = mutable.value
            val ticket = ++generation
            mutable.value = VpnDataUpdateState(initial)
            try {
                action(ticket)
            } catch (cancelled: CancellationException) {
                if (mutable.value.phase != VpnDataUpdatePhase.ERROR) mutable.value = previousState.copy(downloadedBytes = 0, totalBytes = 0)
                throw cancelled
            } catch (failure: DataUpdateFailure) {
                mutable.value = VpnDataUpdateState(VpnDataUpdatePhase.ERROR, error = failure.kind)
            } finally {
                generation++
            }
        }
    }

    private inline fun <T> stage(kind: VpnDataUpdateError, action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: DataUpdateFailure) {
        throw failure
    } catch (failure: Exception) {
        throw DataUpdateFailure(if (failure is IllegalArgumentException && kind == VpnDataUpdateError.NETWORK) VpnDataUpdateError.VERIFICATION else kind, failure)
    } catch (failure: LinkageError) {
        if (kind != VpnDataUpdateError.NATIVE) throw failure
        throw DataUpdateFailure(kind, failure)
    }

    private class DataUpdateFailure(val kind: VpnDataUpdateError, cause: Throwable) : Exception(cause)
}

private class MemoryCheckedCache : FingerprintUpdateCache {
    private var value: FingerprintBundle? = null
    override fun read(): FingerprintBundle? = value
    override fun write(bundle: FingerprintBundle) { value = bundle }
    override fun remove() { value = null }
}
