package com.foxhole.vpn.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal class VpnApplicationIdentityResolver(context: Context) {
    private val manager = context.applicationContext.packageManager

    fun signingCertificateSha256(packageName: String): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, flags)
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        signatures?.map { it.toByteArray() }?.minWithOrNull { left, right ->
            val count = minOf(left.size, right.size)
            var comparison = 0
            for (index in 0 until count) {
                comparison = left[index].toUByte().compareTo(right[index].toUByte())
                if (comparison != 0) break
            }
            if (comparison == 0) left.size.compareTo(right.size) else comparison
        }?.let { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { byte -> "%02x".format(byte) } }
    }.getOrNull()
}
