package com.foxhole.vpn.appupdates

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal fun inspectVpnApkArchive(apk: File): Set<String> = inspectVpnApkNativeLibraries(apk).keys

internal fun inspectVpnApkNativeLibraries(apk: File, requireBase: Boolean = true): Map<String, Set<String>> = try {
    ZipFile(apk).use { zip ->
        if (zip.size() !in 2..50_000) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
        val names = mutableSetOf<String>()
        val libraries = linkedMapOf<String, MutableSet<String>>()
        var total = 0L
        val entries = zip.entries()
        val buffer = ByteArray(64 * 1024)
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            val parts = name.removeSuffix("/").split('/')
            val cap = if (name == "AndroidManifest.xml") 2L * 1024 * 1024 else 64L * 1024 * 1024
            if (name.length !in 1..512 || name.startsWith('/') || name.any { it == '\\' || it == ':' || it.isISOControl() } ||
                parts.any { it.isEmpty() || it == "." || it == ".." } || !names.add(name) || entry.size !in 0..cap ||
                entry.compressedSize < 0 || entry.crc < 0 || entry.method !in setOf(ZipEntry.STORED, ZipEntry.DEFLATED) ||
                total + entry.size > 256L * 1024 * 1024) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
            val crc = CRC32()
            var received = 0L
            val header = ByteArray(20)
            var headerBytes = 0
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > entry.size || received > cap) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
                    crc.update(buffer, 0, count)
                    val copied = minOf(count, header.size - headerBytes)
                    if (copied > 0) {
                        buffer.copyInto(header, headerBytes, 0, copied)
                        headerBytes += copied
                    }
                }
            }
            if (received != entry.size || crc.value != entry.crc) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
            total += received
            if (parts[0] == "lib" && name.endsWith(".so")) {
                if (parts.size != 3 || !vpnAppNativeHeaderMatches(parts[1], header, headerBytes) ||
                    !libraries.getOrPut(parts[1]) { linkedSetOf() }.add(parts[2])) {
                    throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
                }
            }
        }
        if ("AndroidManifest.xml" !in names || requireBase && ("classes.dex" !in names || libraries.isEmpty() ||
                libraries.values.any { "libfoxhole_native.so" !in it })) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
        libraries
    }
} catch (failure: VpnAppUpdateFailure) {
    throw failure
} catch (_: Exception) {
    throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
}

internal fun vpnAppNativeHeaderMatches(abi: String, header: ByteArray, bytesRead: Int): Boolean {
    val expected = when (abi) {
        "armeabi-v7a" -> 1 to 40
        "arm64-v8a" -> 2 to 183
        "x86" -> 1 to 3
        "x86_64" -> 2 to 62
        else -> return false
    }
    return bytesRead == 20 && header.size >= 20 &&
        header.take(4) == listOf(127.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) &&
        header[4].toInt() == expected.first && header[5].toInt() == 1 && header[6].toInt() == 1 &&
        ((header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)) == expected.second
}

internal fun vpnAppRunningAbi(installedAbis: Set<String>, supportedAbis: List<String>): String? =
    supportedAbis.firstOrNull(installedAbis::contains)

internal fun vpnAppNativeProfileCompatible(runningAbi: String?, candidate: Map<String, Set<String>>, armv7: Boolean): Boolean {
    val allowed = if (armv7) setOf("armeabi-v7a") else setOf("arm64-v8a", "x86_64")
    return runningAbi != null && runningAbi in allowed && runningAbi in candidate && candidate.keys.all(allowed::contains) &&
        candidate.values.all { "libfoxhole_native.so" in it }
}
