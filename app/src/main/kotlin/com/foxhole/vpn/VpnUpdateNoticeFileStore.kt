package com.foxhole.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class VpnUpdateNoticeFileStore(
    private val file: File,
    private val generation: suspend () -> String = { "legacy" },
) : VpnUpdateNoticeStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "vpn-update-notice"),
        { installedVpnUpdateNoticeGeneration(context.applicationContext) })

    override suspend fun currentInstallGeneration(): String = generation()

    override suspend fun readAcknowledgedRevision(): Int = readAcknowledgedRevision(currentInstallGeneration())

    override suspend fun writeAcknowledgedRevision(revision: Int) = writeAcknowledgedRevision(currentInstallGeneration(), revision)

    override suspend fun readAcknowledgedRevision(generation: String): Int = record(generation).readVersion()

    override suspend fun writeAcknowledgedRevision(generation: String, revision: Int) {
        require(revision == VPN_UPDATE_NOTICE_REVISION) { "notice_revision_unsupported" }
        record(generation).writeVersion(revision)
    }

    private fun record(generation: String): VpnVersionFileStore {
        require(generation == "legacy" || generation.matches(Regex("v[0-9]+-u[0-9]+"))) { "notice_install_generation_invalid" }
        // Keep the old acknowledgement untouched; every committed APK replacement owns a separate slot.
        return VpnVersionFileStore(if (generation == "legacy") file else File(file.parentFile, "${file.name}-$generation"))
    }
}

private suspend fun installedVpnUpdateNoticeGeneration(context: Context): String = withContext(Dispatchers.IO) {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    require(versionCode > 0 && info.lastUpdateTime > 0) { "notice_install_generation_unavailable" }
    "v$versionCode-u${info.lastUpdateTime}"
}
