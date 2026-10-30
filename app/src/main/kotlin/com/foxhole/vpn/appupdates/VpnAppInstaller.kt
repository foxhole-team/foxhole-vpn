package com.foxhole.vpn.appupdates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

internal sealed interface VpnAppInstallAction {
    data class Install(val intent: Intent) : VpnAppInstallAction
    data class PermissionRequired(val intent: Intent) : VpnAppInstallAction
    data class Failed(val error: VpnAppUpdateError) : VpnAppInstallAction
    data object Unavailable : VpnAppInstallAction
}

internal class VpnAppInstaller(context: Context, private val verify: (File, VpnAppUpdateManifest) -> Unit) {
    private val context = context.applicationContext

    fun prepare(apk: File, manifest: VpnAppUpdateManifest): VpnAppInstallAction {
        verify(apk, manifest)
        if (!context.packageManager.canRequestPackageInstalls()) {
            return VpnAppInstallAction.PermissionRequired(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }
        // FileProvider exposes backup-excluded staging, never private cache.
        val directory = File(context.filesDir, "app-updates/install").apply { makePrivateDirectory() }
        val staged = File(directory, "update.apk")
        val temporary = File(directory, "update.partial.apk")
        try {
            FileOutputStream(temporary).use { output ->
                temporary.makePrivateFile()
                apk.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            verify(temporary, manifest)
            atomicMove(temporary, staged)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.app-update-files", staged)
            return VpnAppInstallAction.Install(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("FoxHole VPN update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } finally {
            temporary.delete()
        }
    }
}
