package com.foxhole.vpn.appupdates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VpnAppApkArchivePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(extraName: String = "assets/notices.txt", extraBytes: Int = 12): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            listOf("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to byteArrayOf(2),
                "lib/arm64-v8a/libfoxhole_native.so" to nativeHeader(), extraName to ByteArray(extraBytes)).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    @Test fun boundedBaseApkReportsItsNativeAbi() {
        assertEquals(setOf("arm64-v8a"), inspectVpnApkArchive(archive()))
    }

    @Test fun installedSplitAndUniversal64UpdatesKeepTheRunningAbiAndEveryCoreLibrary() {
        val core = setOf("libfoxhole_native.so")
        val universal = mapOf("arm64-v8a" to core, "x86_64" to core)
        for (abi in universal.keys) {
            assertEquals(abi, vpnAppRunningAbi(setOf(abi), listOf(abi)))
            assertTrue(vpnAppNativeProfileCompatible(abi, universal, armv7 = false))
            assertTrue(vpnAppNativeProfileCompatible(abi, mapOf(abi to core), armv7 = false))
            assertFalse(vpnAppNativeProfileCompatible(abi, universal - abi, armv7 = false))
            assertFalse(vpnAppNativeProfileCompatible(abi, universal + ("armeabi-v7a" to core), armv7 = false))
            assertFalse(vpnAppNativeProfileCompatible(abi, universal + ("x86_64" to emptySet()), armv7 = false))
            assertFalse(vpnAppNativeProfileCompatible(abi, universal, armv7 = true))
        }
        assertTrue(vpnAppNativeProfileCompatible("armeabi-v7a", mapOf("armeabi-v7a" to core), armv7 = true))
        assertFalse(vpnAppNativeProfileCompatible("armeabi-v7a", universal, armv7 = true))
        assertFalse(vpnAppNativeProfileCompatible(null, universal, armv7 = false))
        assertEquals(null, vpnAppRunningAbi(setOf("arm64-v8a"), listOf("x86_64")))
    }

    @Test fun nativeElfClassMachineVersionAndAdvertisedAbiMustAgree() {
        assertTrue(vpnAppNativeHeaderMatches("arm64-v8a", nativeHeader(), 20))
        assertTrue(vpnAppNativeHeaderMatches("armeabi-v7a", nativeHeader(1, 40), 20))
        assertFalse(vpnAppNativeHeaderMatches("armeabi-v7a", nativeHeader(), 20))
        assertFalse(vpnAppNativeHeaderMatches("arm64-v8a", nativeHeader(1, 183), 20))
        assertFalse(vpnAppNativeHeaderMatches("arm64-v8a", nativeHeader(2, 62), 20))
        assertFalse(vpnAppNativeHeaderMatches("arm64-v8a", nativeHeader().also { it[5] = 2 }, 20))
        assertFalse(vpnAppNativeHeaderMatches("arm64-v8a", nativeHeader().also { it[6] = 0 }, 20))
        assertFalse(vpnAppNativeHeaderMatches("arm64-v8a", nativeHeader(), 19))
        assertFalse(vpnAppNativeHeaderMatches("unknown", nativeHeader(), 20))
        assertThrows(VpnAppUpdateFailure::class.java) {
            inspectVpnApkArchive(archive("lib/armeabi-v7a/libother.so", 20))
        }
    }

    @Test fun installedBaseWithoutLibrariesAndAbiSplitsCanBeInspectedSeparately() {
        val base = temporary.newFile()
        val split = temporary.newFile()
        for ((file, entries) in listOf(base to listOf("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to byteArrayOf(2)),
            split to listOf("AndroidManifest.xml" to byteArrayOf(1), "lib/arm64-v8a/libfoxhole_native.so" to nativeHeader()))) {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
        }
        assertTrue(inspectVpnApkNativeLibraries(base, requireBase = false).isEmpty())
        assertEquals(setOf("arm64-v8a"), inspectVpnApkNativeLibraries(split, requireBase = false).keys)
        assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(base) }
        assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(split) }
    }

    private fun nativeHeader(elfClass: Int = 2, machine: Int = 183): ByteArray = ByteArray(20).apply {
        byteArrayOf(127, 69, 76, 70).copyInto(this)
        this[4] = elfClass.toByte()
        this[5] = 1
        this[6] = 1
        this[18] = machine.toByte()
        this[19] = (machine shr 8).toByte()
    }

    @Test fun traversalAbsoluteAndAmbiguousNamesAreRefusedBeforePlatformParsing() {
        for (name in listOf("../profile-store", "/absolute", "assets/../secret", "assets\\evil", "assets//double", "assets/./dot", "assets/control\n")) {
            assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(archive(name)) }
        }
    }

    @Test fun aHighlyCompressedOversizedManifestIsRefusedBeforeInflationOrPlatformParsing() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, size) in listOf("AndroidManifest.xml" to (2 * 1024 * 1024 + 1), "classes.dex" to 1,
                "lib/arm64-v8a/libfoxhole_native.so" to 1)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(ByteArray(size)); zip.closeEntry()
            }
        }
        assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(file) }
    }

    @Test fun duplicatedCentralDirectoryNamesAreRefused() {
        val file = archive("classes.dax")
        val bytes = file.readBytes()
        val before = "classes.dax".toByteArray()
        val after = "classes.dex".toByteArray()
        for (index in 0..bytes.size - before.size) {
            if (before.indices.all { bytes[index + it] == before[it] }) after.copyInto(bytes, index)
        }
        file.writeBytes(bytes)
        assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(file) }
    }

    @Test fun aTamperedStoredPayloadFailsItsCrc() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("AndroidManifest.xml", "classes.dex", "lib/arm64-v8a/libfoxhole_native.so").forEach { name ->
                val bytes = byteArrayOf(1, 2, 3, 4, 5)
                val expectedCrc = java.util.zip.CRC32().apply { update(bytes) }.value
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = expectedCrc })
                zip.write(bytes); zip.closeEntry()
            }
        }
        val bytes = file.readBytes()
        val needle = byteArrayOf(1, 2, 3, 4, 5)
        val index = (0..bytes.size - needle.size).first { at -> needle.indices.all { bytes[at + it] == needle[it] } }
        bytes[index] = 9
        file.writeBytes(bytes)
        assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(file) }
    }

    @Test fun encryptedEntriesAreRejectedByTheArchiveReader() {
        val file = archive()
        val bytes = file.readBytes()
        for (index in 0..bytes.size - 10) {
            if (bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte()) {
                val flagsAt = when {
                    bytes[index + 2] == 3.toByte() && bytes[index + 3] == 4.toByte() -> index + 6
                    bytes[index + 2] == 1.toByte() && bytes[index + 3] == 2.toByte() -> index + 8
                    else -> continue
                }
                bytes[flagsAt] = (bytes[flagsAt].toInt() or 1).toByte()
            }
        }
        file.writeBytes(bytes)
        assertThrows(VpnAppUpdateFailure::class.java) { inspectVpnApkArchive(file) }
    }
}
