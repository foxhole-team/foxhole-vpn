package com.foxhole.vpn.appupdates

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface VpnAppUpdateCache {
    fun read(): VpnAppRelease?
    fun write(release: VpnAppRelease)
    fun remove()
}

internal class FileVpnAppUpdateCache(private val file: File) : VpnAppUpdateCache {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): VpnAppRelease? {
        if (!file.exists()) return null
        if (file.length() !in 1..(512 * 1024)) throw VpnAppUpdateFailure(VpnAppUpdateError.STORAGE)
        val release = json.decodeFromString<VpnAppRelease>(file.readText())
        if (!release.manifest.isValid || release.sizeBytes !in 1..VpnAppUpdatePolicy.MAX_APK_BYTES ||
            !VpnAppUpdatePolicy.releaseAssetUrl(release.downloadUrl) || !VpnAppUpdatePolicy.releasePageUrl(release.releaseUrl)) {
            throw VpnAppUpdateFailure(VpnAppUpdateError.STORAGE)
        }
        return release
    }

    override fun write(release: VpnAppRelease) {
        requireNotNull(file.parentFile).makePrivateDirectory()
        val temporary = File(file.parentFile, "${file.name}.part")
        try {
            FileOutputStream(temporary).use { output ->
                temporary.makePrivateFile()
                output.write(json.encodeToString(release).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicMove(temporary, file)
        } finally { temporary.delete() }
    }

    override fun remove() { if (file.exists() && !file.delete()) throw VpnAppUpdateFailure(VpnAppUpdateError.STORAGE) }
}

internal fun File.makePrivateDirectory() {
    if (!isDirectory && !mkdirs() || !setReadable(false, false) || !setWritable(false, false) || !setExecutable(false, false) ||
        !setReadable(true, true) || !setWritable(true, true) || !setExecutable(true, true)) throw VpnAppUpdateFailure(VpnAppUpdateError.STORAGE)
}

internal fun atomicMove(source: File, target: File) {
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
