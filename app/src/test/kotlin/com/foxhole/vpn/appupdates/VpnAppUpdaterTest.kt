package com.foxhole.vpn.appupdates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VpnAppUpdaterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = VpnAppRelease(VpnAppUpdateManifest(19, "0.1.2", "vpn.apk", "b".repeat(64)),
        "https://github.com/foxhole-team/foxhole-vpn/releases/download/v0.1.2/vpn.apk", 3,
        "https://github.com/foxhole-team/foxhole-vpn/releases/tag/v0.1.2")
    private class Cache : VpnAppUpdateCache {
        var value: VpnAppRelease? = null
        override fun read() = value
        override fun write(release: VpnAppRelease) { value = release }
        override fun remove() { value = null }
    }
    private class Source(val release: VpnAppRelease) : VpnAppUpdateSource {
        var result: VpnAppUpdateCheck = VpnAppUpdateCheck.Available(release)
        var pauseCheck = false
        var pauseDownload = false
        var downloaded = 0
        override suspend fun latest(installedCode: Long): VpnAppUpdateCheck { if (pauseCheck) awaitCancellation(); return result }
        override suspend fun download(release: VpnAppRelease, target: File, progress: (Long, Long) -> Unit) {
            downloaded++
            target.writeBytes(byteArrayOf(1, 2, 3))
            progress(3, 3)
            if (pauseDownload) awaitCancellation()
        }
    }

    @Test fun packageVerifierMustApproveBeforeReadyAndFailedVerificationLeavesNoArtifact() = runTest {
        val directory = temporary.newFolder()
        val source = Source(release)
        val updater = VpnAppUpdater(directory, { 18 }, { _, _ -> throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION) },
            source = source, cache = Cache(), io = StandardTestDispatcher(testScheduler))
        updater.check()
        assertFalse(updater.download())
        assertEquals(VpnAppUpdateError.VERIFICATION, updater.state.value.error)
        assertFalse(File(directory, "download.partial.apk").exists())
        assertFalse(File(directory, "verified-update.apk").exists())
    }

    @Test fun downloadAtomicallyPublishesAValidatedPrivateArtifact() = runTest {
        val directory = temporary.newFolder()
        val cache = Cache()
        var verified = 0
        val updater = VpnAppUpdater(directory, { 18 }, { apk, manifest ->
            assertTrue(byteArrayOf(1, 2, 3).contentEquals(apk.readBytes())); assertEquals(release.manifest, manifest); verified++
        }, source = Source(release), cache = cache, io = StandardTestDispatcher(testScheduler))
        updater.check()
        assertTrue(updater.download())
        assertEquals(VpnAppUpdatePhase.READY, updater.state.value.phase)
        assertEquals(1, verified)
        assertEquals(release, cache.value)
        assertTrue(File(directory, "verified-update.apk").isFile)
        assertFalse(File(directory, "download.partial.apk").exists())
    }

    @Test fun cancellationRemovesThePartialFileAndRestoresAvailableWithoutSwallowingIt() = runTest {
        val directory = temporary.newFolder()
        val source = Source(release).apply { pauseDownload = true }
        var verifies = 0
        val updater = VpnAppUpdater(directory, { 18 }, { _, _ -> verifies++ }, source = source, cache = Cache(), io = StandardTestDispatcher(testScheduler))
        updater.check()
        val job = launch { updater.download() }
        runCurrent()
        assertEquals(VpnAppUpdatePhase.DOWNLOADING, updater.state.value.phase)
        job.cancel(CancellationException("owner cancellation"))
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(VpnAppUpdatePhase.AVAILABLE, updater.state.value.phase)
        assertEquals(0, verifies)
        assertFalse(File(directory, "download.partial.apk").exists())
    }

    @Test fun aBackgroundFailureDoesNotReplaceAnAvailableOrReadyUpdateWithAnError() = runTest {
        val source = Source(release)
        val updater = VpnAppUpdater(temporary.newFolder(), { 18 }, { _, _ -> }, source = source, cache = Cache(), io = StandardTestDispatcher(testScheduler))
        updater.check()
        updater.download()
        source.result = VpnAppUpdateCheck.Failed(VpnAppUpdateError.NETWORK)
        updater.check(manual = false)
        assertEquals(VpnAppUpdatePhase.READY, updater.state.value.phase)
        updater.check(manual = true)
        assertEquals(VpnAppUpdatePhase.ERROR, updater.state.value.phase)
        assertEquals(VpnAppUpdateError.NETWORK, updater.state.value.error)
    }

    @Test fun installedCodeIsReadAgainBeforeDownloadAndCachedOlderCandidatesAreCleared() = runTest {
        val directory = temporary.newFolder()
        val cache = Cache()
        val source = Source(release)
        var installed = 18L
        val updater = VpnAppUpdater(directory, { installed }, { _, _ -> }, source = source, cache = cache, io = StandardTestDispatcher(testScheduler))
        updater.check()
        installed = 19
        assertFalse(updater.download())
        assertEquals(0, source.downloaded)
        val restored = VpnAppUpdater(directory, { installed }, { _, _ -> error("old package is not verified") }, source = source, cache = cache, io = StandardTestDispatcher(testScheduler))
        restored.restore()
        assertEquals(VpnAppUpdatePhase.UP_TO_DATE, restored.state.value.phase)
        assertEquals(null, cache.value)
    }

    @Test fun fdroidAndDebugDisableAllAppUpdateNetworkAndDownloadOperations() = runTest {
        val source = Source(release)
        val updater = VpnAppUpdater(temporary.newFolder(), { 18 }, { _, _ -> error("disabled verifier") }, enabled = false,
            source = source, cache = Cache(), io = StandardTestDispatcher(testScheduler))
        assertEquals(VpnAppUpdateCheck.Disabled, updater.check())
        assertFalse(updater.download())
        assertEquals(VpnAppUpdatePhase.DISABLED, updater.state.value.phase)
        assertEquals(0, source.downloaded)
    }
}
