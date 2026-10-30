package com.foxhole.vpn.appupdates

import android.content.Context
import com.foxhole.vpn.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class VpnAppUpdater(
    private val directory: File,
    private val installedCode: () -> Long,
    private val verify: (File, VpnAppUpdateManifest) -> Unit,
    private val enabled: Boolean = true,
    private val source: VpnAppUpdateSource = VpnAppUpdateHttpSource(),
    private val cache: VpnAppUpdateCache = FileVpnAppUpdateCache(File(directory, "release.json")),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(VpnAppUpdateState(if (enabled) VpnAppUpdatePhase.IDLE else VpnAppUpdatePhase.DISABLED))
    val state: StateFlow<VpnAppUpdateState> = mutable.asStateFlow()
    private val lock = Mutex()
    private val artifact = File(directory, "verified-update.apk")
    @Volatile private var generation = 0L

    suspend fun restore() = withContext(io) {
        lock.withLock {
            if (!enabled || mutable.value.phase != VpnAppUpdatePhase.IDLE) return@withLock
            try {
                File(directory, "download.partial.apk").delete()
                File(directory, "release.json.part").delete()
                val release = cache.read() ?: run { artifact.delete(); return@withLock }
                if (release.manifest.versionCode <= installedCode()) {
                    clearArtifacts()
                    mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.UP_TO_DATE)
                } else {
                    val ready = artifact.isFile && try {
                        verify(artifact, release.manifest)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { artifact.delete(); false }
                    mutable.value = VpnAppUpdateState(if (ready) VpnAppUpdatePhase.READY else VpnAppUpdatePhase.AVAILABLE, release)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                artifact.delete()
                runCatching { cache.remove() }
            }
        }
    }

    suspend fun check(manual: Boolean = true): VpnAppUpdateCheck = withContext(io) {
        lock.withLock {
            if (!enabled) return@withLock VpnAppUpdateCheck.Disabled
            val previous = mutable.value
            if (manual) mutable.value = previous.copy(phase = VpnAppUpdatePhase.CHECKING, error = null)
            try {
                val result = source.latest(installedCode())
                currentCoroutineContext().ensureActive()
                when (result) {
                    is VpnAppUpdateCheck.Available -> {
                        storage { cache.write(result.release) }
                        val ready = previous.phase == VpnAppUpdatePhase.READY && previous.release == result.release && artifact.isFile
                        mutable.value = VpnAppUpdateState(if (ready) VpnAppUpdatePhase.READY else VpnAppUpdatePhase.AVAILABLE, result.release)
                    }
                    VpnAppUpdateCheck.NoRelease -> { clearArtifacts(); mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.NO_RELEASE) }
                    VpnAppUpdateCheck.UpToDate -> { clearArtifacts(); mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.UP_TO_DATE) }
                    VpnAppUpdateCheck.Disabled -> Unit
                    is VpnAppUpdateCheck.Failed -> if (manual) mutable.value = previous.copy(phase = VpnAppUpdatePhase.ERROR, error = result.error)
                }
                result
            } catch (cancelled: CancellationException) {
                mutable.value = previous
                throw cancelled
            } catch (failure: Exception) {
                val kind = failureKind(failure)
                if (manual) mutable.value = previous.copy(phase = VpnAppUpdatePhase.ERROR, error = kind)
                VpnAppUpdateCheck.Failed(kind)
            }
        }
    }

    suspend fun download(): Boolean = withContext(io) {
        lock.withLock {
            if (!enabled) return@withLock false
            val previous = mutable.value
            val release = previous.release ?: return@withLock false
            if (release.manifest.versionCode <= installedCode()) return@withLock false
            val temporary = File(directory, "download.partial.apk")
            val ticket = ++generation
            val context = currentCoroutineContext()
            mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.DOWNLOADING, release, totalBytes = release.sizeBytes)
            try {
                storage { directory.makePrivateDirectory(); temporary.delete() }
                source.download(release, temporary) { received, total ->
                    if (generation == ticket && context.isActive) mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.DOWNLOADING, release, received, total)
                }
                context.ensureActive()
                mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.VERIFYING, release)
                verification { verify(temporary, release.manifest) }
                context.ensureActive()
                storage { atomicMove(temporary, artifact); cache.write(release) }
                context.ensureActive()
                mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.READY, release, release.sizeBytes, release.sizeBytes)
                true
            } catch (cancelled: CancellationException) {
                mutable.value = previous.copy(downloadedBytes = 0, totalBytes = 0)
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = VpnAppUpdateState(VpnAppUpdatePhase.ERROR, release, error = failureKind(failure))
                false
            } finally {
                generation++
                temporary.delete()
            }
        }
    }

    suspend fun prepareInstall(context: Context): VpnAppInstallAction = withContext(io) {
        lock.withLock {
            val current = mutable.value
            val release = current.release
            if (!enabled || current.phase != VpnAppUpdatePhase.READY || release == null) return@withLock VpnAppInstallAction.Unavailable
            try {
                VpnAppInstaller(context, verify).prepare(artifact, release.manifest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val kind = if (failure is VpnAppUpdateFailure) failure.kind else VpnAppUpdateError.INSTALLER
                mutable.value = current.copy(phase = VpnAppUpdatePhase.ERROR, error = kind)
                VpnAppInstallAction.Failed(kind)
            }
        }
    }

    private fun clearArtifacts() = storage {
        cache.remove()
        if (artifact.exists() && !artifact.delete()) throw IOException("app update cleanup")
        File(directory, "download.partial.apk").delete()
    }

    private inline fun <T> storage(action: () -> T): T = try { action() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { throw VpnAppUpdateFailure(VpnAppUpdateError.STORAGE) }

    private inline fun <T> verification(action: () -> T): T = try { action() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION) }

    private fun failureKind(error: Exception): VpnAppUpdateError = when (error) {
        is VpnAppUpdateFailure -> error.kind
        is IOException -> VpnAppUpdateError.NETWORK
        else -> VpnAppUpdateError.METADATA
    }

    companion object {
        fun create(context: Context): VpnAppUpdater {
            val app = context.applicationContext
            val verifier = VpnAppApkVerifier(app)
            return VpnAppUpdater(File(app.noBackupFilesDir, "app-updates"), verifier::installedCode, verifier::verify,
                VpnAppUpdatePolicy.allowed(app.packageName, BuildConfig.APP_UPDATE_CHANNEL),
                source = VpnAppUpdateHttpSource(manifestAssetName = BuildConfig.UPDATE_MANIFEST_ASSET))
        }
    }
}
