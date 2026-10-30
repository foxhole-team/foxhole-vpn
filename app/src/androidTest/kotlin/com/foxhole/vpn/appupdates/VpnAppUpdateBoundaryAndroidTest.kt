package com.foxhole.vpn.appupdates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class VpnAppUpdateBoundaryAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun providerExposesOnlyTheBackupExcludedInstallerDirectory() {
        val authority = "${context.packageName}.app-update-files"
        val directory = File(context.filesDir, "app-updates/install").apply { mkdirs() }
        val owned = File(directory, "boundary.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            assertEquals("content", FileProvider.getUriForFile(context, authority, owned).scheme)
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, File(context.noBackupFilesDir, "profile-store"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, File(context.filesDir, "other-private-state"))
            }
            val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
        } finally { owned.delete() }
    }

    @Test fun permissionAndInstallerIntentsRequireThePlatformFlowWithoutStartingIt() {
        val file = File(context.noBackupFilesDir, "app-update-boundary.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manifest = VpnAppUpdateManifest(19, "0.1.2", "vpn.apk", "b".repeat(64))
        var verifications = 0
        val installer = VpnAppInstaller(context) { candidate, announced ->
            assertTrue(candidate.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(manifest, announced)
            verifications++
        }
        try {
            when (val prepared = installer.prepare(file, manifest)) {
                is VpnAppInstallAction.PermissionRequired -> {
                    assertFalse(context.packageManager.canRequestPackageInstalls())
                    assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, prepared.intent.action)
                    assertEquals(Uri.parse("package:${context.packageName}"), prepared.intent.data)
                    assertEquals(1, verifications)
                }
                is VpnAppInstallAction.Install -> {
                    assertTrue(context.packageManager.canRequestPackageInstalls())
                    assertEquals(Intent.ACTION_VIEW, prepared.intent.action)
                    assertEquals("application/vnd.android.package-archive", prepared.intent.type)
                    assertEquals("content", prepared.intent.data?.scheme)
                    assertTrue(prepared.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertEquals(0, prepared.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    assertEquals(2, verifications)
                }
                else -> error("installer did not return a platform action")
            }
        } finally {
            file.delete()
            File(context.filesDir, "app-updates/install/update.apk").delete()
        }
    }

    @Test fun theInstalledApkCannotBeOfferedAsItsOwnUpgrade() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val apk = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val manifest = VpnAppUpdateManifest(VpnAppApkVerifier(context).installedCode(), requireNotNull(packageInfo.versionName),
            "vpn.apk", digest.digest().hexDigest())
        val failure = assertThrows(VpnAppUpdateFailure::class.java) { VpnAppApkVerifier(context).verify(apk, manifest) }
        assertEquals(VpnAppUpdateError.VERIFICATION, failure.kind)
    }
}
