package com.foxhole.vpn.updates

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VpnDataUpdaterTest {
    private val fixture = FingerprintFeedFixture()

    private class Cache : FingerprintUpdateCache {
        var value: FingerprintBundle? = null
        var failWrite = false
        var afterWrite: () -> Unit = {}
        override fun read(): FingerprintBundle? = value
        override fun remove() { value = null }
        override fun write(bundle: FingerprintBundle) {
            if (failWrite) throw IOException("owned write failure")
            value = bundle
            afterWrite()
        }
    }

    private class Source(var bundle: FingerprintBundle) : FingerprintUpdateSource {
        var pause = false
        var pauseManifest = false
        var fetches = 0
        override suspend fun manifest(): FingerprintManifestBytes {
            if (pauseManifest) awaitCancellation()
            return FingerprintManifestBytes(bundle.manifest, bundle.signature)
        }
        override suspend fun document(expectedBytes: Long, onProgress: (Long) -> Unit): ByteArray {
            fetches++
            if (pause) awaitCancellation()
            onProgress(bundle.document.size.toLong())
            return bundle.document
        }
    }

    @Test fun missingCacheDoesNotRequireNativeInitialization() = runTest {
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { error("native install on fresh startup") },
            { error("native clear on fresh startup") }, Source(fixture.bundle()), fixture.verifier,
            Cache(), StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        updater.restore()
        assertEquals(VpnDataUpdatePhase.IDLE, updater.state.value.phase)
    }

    @Test fun manualMetadataDownloadKeepsTheCardBusyWithoutInventingAByteTotal() = runTest {
        val source = Source(fixture.bundle())
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { error("native install before metadata") }, {}, source,
            fixture.verifier, Cache(), StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests.mapValues { "0".repeat(64) })
        updater.check()
        val before = updater.state.value
        assertEquals(VpnDataUpdatePhase.AVAILABLE, before.phase)
        source.pauseManifest = true
        val update = launch { updater.update() }
        try {
            runCurrent()
            assertEquals(VpnDataUpdatePhase.DOWNLOADING, updater.state.value.phase)
            assertTrue(updater.state.value.isBusy)
            assertEquals(0L, updater.state.value.downloadedBytes)
            assertEquals(0L, updater.state.value.totalBytes)
        } finally {
            update.cancel()
            runCurrent()
        }
        assertTrue(update.isCancelled)
        assertEquals(before, updater.state.value)
    }

    @Test fun checkComparesTrustedBaselineWithoutInstalling() = runTest {
        val source = Source(fixture.bundle())
        var installs = 0
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { installs++; 4 }, {}, source,
            fixture.verifier, Cache(), StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        updater.check()
        assertEquals(VpnDataUpdatePhase.UP_TO_DATE, updater.state.value.phase)
        assertEquals(0, installs)
        assertEquals(1, source.fetches)
    }

    @Test fun changedCandidateIsOnlyUpdatedAfterNativeAcceptanceAndPersistence() = runTest {
        val cache = Cache()
        val source = Source(fixture.bundle())
        var active: ByteArray? = null
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { active = it; 4 }, {}, source,
            fixture.verifier, cache, StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests.mapValues { "0".repeat(64) })
        updater.check()
        assertEquals(VpnDataUpdatePhase.AVAILABLE, updater.state.value.phase)
        assertEquals(null, active)
        updater.update()
        assertEquals(VpnDataUpdatePhase.UPDATED, updater.state.value.phase)
        assertTrue(cache.value!!.document.contentEquals(active!!))
        assertEquals(2, source.fetches)
    }

    @Test fun nativeRefusalPreservesLastWorkingRegistryAndCache() = runTest {
        val cache = Cache().apply { value = fixture.bundle(fixture.now.minusSeconds(1)) }
        val previous = cache.value!!
        var clears = 0
        var reject = false
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { if (reject) -2 else 4 }, { clears++ }, Source(fixture.bundle()),
            fixture.verifier, cache, StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests.mapValues { "0".repeat(64) })
        updater.restore()
        reject = true
        updater.update()
        assertEquals(VpnDataUpdateError.NATIVE, updater.state.value.error)
        assertEquals(previous, cache.value)
        assertEquals(0, clears)
    }

    @Test fun failedAtomicWriteRestoresPreviousNativeDocument() = runTest {
        val cache = Cache().apply { value = fixture.bundle(fixture.now.minusSeconds(1)) }
        val previous = cache.value!!.document
        var active: ByteArray? = null
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { active = it; 4 }, {}, Source(fixture.bundle()),
            fixture.verifier, cache, StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests.mapValues { "0".repeat(64) })
        updater.restore()
        cache.failWrite = true
        updater.update()
        assertEquals(VpnDataUpdateError.STORAGE, updater.state.value.error)
        assertTrue(previous.contentEquals(active!!))
    }

    @Test fun cancelledDownloadNeitherInstallsNorLeavesBusyState() = runTest {
        val cache = Cache()
        val source = Source(fixture.bundle()).apply { pause = true }
        var installs = 0
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { installs++; 4 }, {}, source,
            fixture.verifier, cache, StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        val job = launch { updater.update() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(updater.state.value.isBusy)
        assertEquals(null, cache.value)
        assertEquals(0, installs)
    }

    @Test fun manualUpdateWaitsForTheCancelledForegroundCheck() = runTest {
        val source = Source(fixture.bundle()).apply { pause = true }
        var installs = 0
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { installs++; 4 }, {}, source,
            fixture.verifier, Cache(), StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        val check = launch { updater.check() }
        runCurrent()
        val update = launch { updater.update() }
        runCurrent()
        source.pause = false
        check.cancel()
        runCurrent()
        assertTrue(update.isCompleted)
        assertEquals(1, installs)
        assertEquals(VpnDataUpdatePhase.UP_TO_DATE, updater.state.value.phase)
    }

    @Test fun cancellationInsideNativeAdmissionRestoresCompiledFallback() = runTest {
        val cache = Cache()
        lateinit var update: Job
        var active: ByteArray? = null
        var clears = 0
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { active = it; update.cancel(); 4 }, { active = null; clears++ },
            Source(fixture.bundle()), fixture.verifier, cache, StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        update = launch { updater.update() }
        runCurrent()
        assertTrue(update.isCancelled)
        assertEquals(1, clears)
        assertEquals(null, active)
        assertEquals(null, cache.value)
        assertFalse(updater.state.value.isBusy)
    }

    @Test fun cancellationAfterAtomicWriteRestoresThePreviousSignedEnvelopeAndRegistry() = runTest {
        val cache = Cache().apply { value = fixture.bundle(fixture.now.minusSeconds(1)) }
        val previous = cache.value!!
        lateinit var update: Job
        var active: ByteArray? = null
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { active = it; 4 }, {}, Source(fixture.bundle()),
            fixture.verifier, cache, StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        updater.restore()
        cache.afterWrite = { update.cancel() }
        update = launch { updater.update() }
        runCurrent()
        assertTrue(update.isCancelled)
        assertEquals(previous, cache.value)
        assertTrue(previous.document.contentEquals(active!!))
        assertFalse(updater.state.value.isBusy)
    }

    @Test fun cancellationKeepsARollbackFailureVisible() = runTest {
        lateinit var update: Job
        val updater = VpnDataUpdater(File("unused"), "0.1.1", { update.cancel(); 4 }, { error("owned rollback failure") },
            Source(fixture.bundle()), fixture.verifier, Cache(), StandardTestDispatcher(testScheduler), { fixture.now }, fixture.digests)
        update = launch { updater.update() }
        runCurrent()
        assertTrue(update.isCancelled)
        assertEquals(VpnDataUpdatePhase.ERROR, updater.state.value.phase)
        assertEquals(VpnDataUpdateError.NATIVE, updater.state.value.error)
    }
}
