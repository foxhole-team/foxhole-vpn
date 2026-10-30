package com.foxhole.vpn.appupdates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.foxhole.vpn.BuildConfig
import java.io.File
import java.security.MessageDigest

internal class VpnAppApkVerifier(context: Context) {
    private val context = context.applicationContext

    fun installedCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).versionCodeCompat()

    fun verify(apk: File, manifest: VpnAppUpdateManifest) {
        if (!manifest.isValid || !apk.isFile || apk.length() !in 1..VpnAppUpdatePolicy.MAX_APK_BYTES) {
            throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().hexDigest() != manifest.apkSha256.lowercase()) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
        val installedPaths = listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()
        val installedAbis = installedPaths.flatMap { inspectVpnApkNativeLibraries(File(it), requireBase = false).keys }.toSet()
        val supportedAbis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        if (!vpnAppNativeProfileCompatible(vpnAppRunningAbi(installedAbis, supportedAbis.toList()),
                inspectVpnApkNativeLibraries(apk), BuildConfig.UPDATE_MANIFEST_ASSET == VpnAppUpdatePolicy.ARMV7_MANIFEST_ASSET)) {
            throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
        }
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
            ?: throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
        val installed = manager.getPackageInfo(context.packageName, signingFlags())
        if (!VpnAppUpdatePolicy.packageMatches(
                archive.packageName, context.packageName, archive.versionCodeCompat(), installed.versionCodeCompat(),
                manifest.versionCode, archive.versionName, manifest.versionName, installed.signerDigests(), archive.signerDigests(),
            ) || archive.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } != false ||
            !archive.splitNames.isNullOrEmpty()) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
    }

    private fun PackageInfo.signerDigests(): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION")
            signatures
        }
        return signers.orEmpty().mapTo(mutableSetOf()) { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hexDigest() }
    }

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }
}

private fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
    @Suppress("DEPRECATION")
    versionCode.toLong()
}
