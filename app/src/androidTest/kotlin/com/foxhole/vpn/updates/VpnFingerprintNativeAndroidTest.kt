package com.foxhole.vpn.updates

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.runtime.FoxholeNativeEngine
import com.foxhole.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class VpnFingerprintNativeAndroidTest {
    @Test
    fun realNativeRegistryAcceptsPinnedTablesAndRejectsMalformedDocument() {
        val document = InstrumentationRegistry.getInstrumentation().context.assets
            .open("fingerprints/core-ea090-tables.json").use { it.readBytes() }
        // This Guard seed contains the nine unchanged profile objects from committed Core ea090.
        val digest = MessageDigest.getInstance("SHA-256").digest(document).joinToString("") { "%02x".format(it) }
        assertEquals("97e991577a2094fb6db47dfc9a25b64d2ed4ccf1d4ad3f5a15a0b72b5722386f", digest)

        try {
            assertEquals(1, FoxholeNativeEngine.nativeAbiVersion())
            assertEquals(9, FoxholeNativeEngine.nativeInstallTlsFingerprintTables(document))
            assertEquals(
                FoxholeNativeEngine.TLS_FINGERPRINT_TABLES_REFUSED,
                FoxholeNativeEngine.nativeInstallTlsFingerprintTables(byteArrayOf('{'.code.toByte())),
            )
        } finally {
            FoxholeNativeEngine.nativeClearTlsFingerprintTables()
        }
    }

    @Test
    fun genuineSignedUpdatePersistsAndReauthenticatesThroughRealAndroidNativeRegistry() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bundle = FingerprintBundle(
            assets.open("fingerprints/official-20261007-manifest.json").use { it.readBytes() },
            assets.open("fingerprints/official-20261007-manifest.json.sig").use { it.readBytes() },
            assets.open("fingerprints/official-20261007-tables.json").use { it.readBytes() },
        )
        // Pins the unmodified signed feed copied from the verified 2026-10-07 official publication.
        assertEquals("89a3aca418592bb4ab686d56b90584c869ebcf3b0de66cd1061216f05cd68cb5", sha256(bundle.manifest))
        assertEquals("6e2b00078fb3e89bdaff49698295c160c0cf802575fd3a9d6eac01bccc317528", sha256(bundle.signature))
        assertEquals("73c2d86a587596e4aa4b9a568247ba9d7174f747950a79e0b0a55854d5d36013", sha256(bundle.document))
        val now = Instant.parse("2026-10-07T10:18:37Z").plusSeconds(60)
        val verifier = FingerprintFeedVerifier(BuildConfig.VERSION_NAME)
        val verified = verifier.verify(bundle, now)
        assertEquals(FingerprintBaseline.digests, verified.profileDigests)
        assertEquals(9, verified.profileDigests.size)

        val directory = Files.createTempDirectory(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
            "owned-fingerprint-native-",
        ).toFile()
        val nativeAdmissions = mutableListOf<Int>()
        var nativeClears = 0
        val source = object : FingerprintUpdateSource {
            override suspend fun manifest() = FingerprintManifestBytes(bundle.manifest, bundle.signature)

            override suspend fun document(expectedBytes: Long, onProgress: (Long) -> Unit): ByteArray {
                assertEquals(bundle.document.size.toLong(), expectedBytes)
                onProgress(bundle.document.size.toLong())
                return bundle.document
            }
        }
        fun updater() = VpnDataUpdater(
            directory = directory,
            currentVersion = BuildConfig.VERSION_NAME,
            installTables = { document ->
                FoxholeNativeEngine.nativeInstallTlsFingerprintTables(document).also { nativeAdmissions.add(it) }
            },
            clearTables = {
                nativeClears++
                FoxholeNativeEngine.nativeClearTlsFingerprintTables()
            },
            source = source,
            io = Dispatchers.IO,
            now = { now },
        )

        try {
            FoxholeNativeEngine.nativeClearTlsFingerprintTables()
            val first = updater()
            first.check()
            assertEquals(VpnDataUpdatePhase.UP_TO_DATE, first.state.value.phase)
            assertTrue(first.hasVerifiedCheckCache)
            assertTrue(nativeAdmissions.isEmpty())
            val checkedFile = directory.resolve("checked-core-fingerprint-tables.v1")
            val installedFile = directory.resolve("core-fingerprint-tables.v1")
            assertEquals(listOf(checkedFile.name), requireNotNull(directory.list()).sorted())
            assertSignedCache(checkedFile, bundle, verifier, now, fresh = true)

            first.update()
            assertEquals(VpnDataUpdatePhase.UP_TO_DATE, first.state.value.phase)
            assertTrue(first.hasVerifiedCheckCache)
            assertEquals(listOf(9), nativeAdmissions)
            assertEquals(listOf(checkedFile.name, installedFile.name), requireNotNull(directory.list()).sorted())
            val checked = assertSignedCache(checkedFile, bundle, verifier, now, fresh = true)
            val installed = assertSignedCache(installedFile, bundle, verifier, now, fresh = false)
            checked.manifest.requireNotOlderThan(installed.manifest)
            assertEquals(installed.manifest, checked.manifest)

            FoxholeNativeEngine.nativeClearTlsFingerprintTables()
            val restored = updater()
            restored.restore()
            assertEquals(listOf(9, 9), nativeAdmissions)
            assertEquals(VpnDataUpdatePhase.UP_TO_DATE, restored.state.value.phase)
            assertTrue(restored.hasVerifiedCheckCache)
            assertNull(restored.state.value.error)
            assertEquals(0, nativeClears)

            val signature = bundle.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            FileFingerprintUpdateCache(checkedFile).write(bundle.copy(signature = signature))
            val rejected = updater()
            rejected.restore()
            assertEquals(listOf(9, 9, 9), nativeAdmissions)
            assertEquals(VpnDataUpdatePhase.ERROR, rejected.state.value.phase)
            assertEquals(VpnDataUpdateError.VERIFICATION, rejected.state.value.error)
            assertFalse(rejected.hasVerifiedCheckCache)
            assertFalse(checkedFile.exists())
            assertEquals(listOf(installedFile.name), requireNotNull(directory.list()).sorted())
            assertSignedCache(installedFile, bundle, verifier, now, fresh = false)
            assertEquals(0, nativeClears)
        } finally {
            try {
                FoxholeNativeEngine.nativeClearTlsFingerprintTables()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun assertSignedCache(
        file: File,
        expected: FingerprintBundle,
        verifier: FingerprintFeedVerifier,
        now: Instant,
        fresh: Boolean,
    ): VerifiedFingerprintBundle {
        assertEquals(OsConstants.S_IRUSR or OsConstants.S_IWUSR, Os.stat(file.absolutePath).st_mode and 0x1FF)
        assertEquals("62e504c1c89f3494065d86ab5b7ab481be7282ba1b7539e6c0e68e4d9422b5bf", sha256(file.readBytes()))
        val persisted = requireNotNull(FileFingerprintUpdateCache(file).read())
        assertArrayEquals(expected.manifest, persisted.manifest)
        assertArrayEquals(expected.signature, persisted.signature)
        assertArrayEquals(expected.document, persisted.document)
        return verifier.verify(persisted, now, fresh).also {
            assertEquals(FingerprintBaseline.digests, it.profileDigests)
            assertEquals(9, it.profileDigests.size)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
