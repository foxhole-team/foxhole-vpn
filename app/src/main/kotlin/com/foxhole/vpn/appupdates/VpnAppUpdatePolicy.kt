package com.foxhole.vpn.appupdates

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object VpnAppUpdatePolicy {
    const val RELEASES_API = "https://api.github.com/repos/foxhole-team/foxhole-vpn/releases/latest"
    const val RELEASE_CERT_SHA256 = "e59de2486084c38f3c77e9df0eb5eff9a4559f3c68024f1208e0e9c04b0df665"
    const val MANIFEST_ASSET = "update-manifest.json"
    const val ARMV7_MANIFEST_ASSET = "update-manifest-armeabi-v7a.json"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val CHECK_INTERVAL_HOURS = 24L
    private const val DOWNLOAD_PATH = "/foxhole-team/foxhole-vpn/releases/download/"

    fun allowed(packageName: String, channel: String): Boolean = packageName == "com.foxhole.vpn" && channel == "github"

    fun releaseAssetUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == "github.com" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty() && url.encodedPath.startsWith(DOWNLOAD_PATH) &&
            url.pathSegments.size == 6 && url.pathSegments.takeLast(2).all(String::isNotBlank) && url.fragment == null
    }

    fun downloadHopAllowed(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    fun releasePageUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == "github.com" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty() && url.encodedPath.startsWith("/foxhole-team/foxhole-vpn/releases/tag/") &&
            url.pathSegments.size == 5 && url.pathSegments.last().isNotBlank() && url.fragment == null
    }

    fun packageMatches(
        candidatePackage: String,
        installedPackage: String,
        candidateCode: Long,
        installedCode: Long,
        manifestCode: Long,
        candidateName: String?,
        manifestName: String,
        installedSigners: Set<String>,
        candidateSigners: Set<String>,
    ): Boolean = installedPackage == "com.foxhole.vpn" && candidatePackage == installedPackage &&
        candidateCode == manifestCode && candidateCode > installedCode && candidateName == manifestName &&
        installedSigners == setOf(RELEASE_CERT_SHA256) && candidateSigners == installedSigners
}
