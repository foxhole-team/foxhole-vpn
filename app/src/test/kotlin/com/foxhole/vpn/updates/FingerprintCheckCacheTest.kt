package com.foxhole.vpn.updates

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintCheckCacheTest {
    private val fixture = FingerprintFeedFixture()

    private class Cache(var value: FingerprintBundle? = null) : FingerprintUpdateCache {
        var failWrite = false
        override fun read(): FingerprintBundle? = value
        override fun write(bundle: FingerprintBundle) {
            if (failWrite) throw IOException("owned check-cache failure")
            value = bundle
        }
        override fun remove() { value = null }
    }

    private class Source(private val bundle: FingerprintBundle) : FingerprintUpdateSource {
        override suspend fun manifest() = FingerprintManifestBytes(bundle.manifest, bundle.signature)
        override suspend fun document(expectedBytes: Long, onProgress: (Long) -> Unit) = bundle.document
    }

    private fun updater(
        dispatcher: CoroutineDispatcher,
        installed: Cache = Cache(),
        checked: Cache = Cache(),
        baseline: Map<String, String> = fixture.digests,
        install: (ByteArray) -> Int = { error("candidate cannot install") },
        clear: () -> Unit = {},
    ) = VpnDataUpdater(
        directory = File("unused"),
        currentVersion = "0.1.1",
        installTables = install,
        clearTables = clear,
        source = Source(fixture.bundle()),
        verifier = fixture.verifier,
        cache = installed,
        io = dispatcher,
        now = { fixture.now },
        baselineDigests = baseline,
        checkCache = checked,
    )

    @Test fun authenticatedAvailableCheckSurvivesAProcessRestartWithoutNativeInstallation() = runTest {
        val checked = Cache()
        val baseline = fixture.digests.mapValues { "0".repeat(64) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = updater(dispatcher, checked = checked, baseline = baseline)
        first.check()
        assertEquals(VpnDataUpdatePhase.AVAILABLE, first.state.value.phase)
        val restored = updater(dispatcher, checked = checked, baseline = baseline)
        restored.restore()
        assertEquals(VpnDataUpdatePhase.AVAILABLE, restored.state.value.phase)
        assertTrue(restored.hasVerifiedCheckCache)
    }

    @Test fun cachedTablesMatchingTheCurrentBaselineDoNotInventAnAvailableUpdate() = runTest {
        val updater = updater(StandardTestDispatcher(testScheduler), checked = Cache(fixture.bundle()))
        updater.restore()
        assertEquals(VpnDataUpdatePhase.UP_TO_DATE, updater.state.value.phase)
        assertTrue(updater.hasVerifiedCheckCache)
    }

    @Test fun tamperedOrExpiredObservationForcesAnotherCheckAndRetainsOfflineInstalledTables() = runTest {
        val installed = fixture.bundle(fixture.now.minus(46, ChronoUnit.DAYS))
        val tampered = fixture.bundle().let { it.copy(signature = it.signature.copyOf().apply { this[0] = 0 }) }
        for (candidate in listOf(tampered, installed)) {
            val checked = Cache(candidate)
            var nativeInstalls = 0
            val updater = updater(
                StandardTestDispatcher(testScheduler),
                installed = Cache(installed),
                checked = checked,
                install = {
                    nativeInstalls++
                    4
                },
                clear = { error("keep installed tables") },
            )
            updater.restore()
            assertEquals(1, nativeInstalls)
            assertFalse(updater.hasVerifiedCheckCache)
            assertNull(checked.value)
            assertEquals(VpnDataUpdatePhase.ERROR, updater.state.value.phase)
        }
    }

    @Test fun aCheckCacheWriteFailureCannotReportVerifiedAvailability() = runTest {
        val checked = Cache().apply { failWrite = true }
        val updater = updater(
            StandardTestDispatcher(testScheduler),
            checked = checked,
            baseline = fixture.digests.mapValues { "0".repeat(64) },
        )
        updater.check()
        assertEquals(VpnDataUpdatePhase.ERROR, updater.state.value.phase)
        assertEquals(VpnDataUpdateError.STORAGE, updater.state.value.error)
        assertFalse(updater.hasVerifiedCheckCache)
    }
}
