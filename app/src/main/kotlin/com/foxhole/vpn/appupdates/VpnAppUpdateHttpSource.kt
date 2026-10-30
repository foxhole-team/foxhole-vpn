package com.foxhole.vpn.appupdates

import com.foxhole.core.network.isPrivateOrLocalAddress
import com.foxhole.core.network.isTunnelSynthesizedAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface VpnAppUpdateSource {
    suspend fun latest(installedCode: Long): VpnAppUpdateCheck
    suspend fun download(release: VpnAppRelease, target: File, progress: (Long, Long) -> Unit)
}

internal class VpnAppUpdateHttpSource(
    callFactory: Call.Factory? = null,
    private val manifestAssetName: String = VpnAppUpdatePolicy.MANIFEST_ASSET,
) : VpnAppUpdateSource {
    init {
        require(manifestAssetName in setOf(VpnAppUpdatePolicy.MANIFEST_ASSET, VpnAppUpdatePolicy.ARMV7_MANIFEST_ASSET))
    }
    private val calls by lazy { callFactory ?: guardedClient() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(installedCode: Long): VpnAppUpdateCheck =
        withTimeoutOrNull(60_000) { latestResult(installedCode) } ?: VpnAppUpdateCheck.Failed(VpnAppUpdateError.NETWORK)

    private suspend fun latestResult(installedCode: Long): VpnAppUpdateCheck = try {
        val release = json.decodeFromString<GithubRelease>(text(VpnAppUpdatePolicy.RELEASES_API, api = true))
        if (release.draft || release.prerelease) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        if (!VpnAppUpdatePolicy.releasePageUrl(release.htmlUrl)) {
            throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        }
        val manifests = release.assets.filter { it.name == manifestAssetName }
        if (manifests.size != 1) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        val manifestAsset = manifests.single()
        requireAsset(manifestAsset)
        val manifest = json.decodeFromString<VpnAppUpdateManifest>(text(manifestAsset.browserDownloadUrl))
        if (!manifest.isValid || release.tagName != "v${manifest.versionName}" ||
            release.htmlUrl.toHttpUrl().pathSegments.last() != release.tagName) {
            throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        }
        if (manifest.versionCode <= installedCode) VpnAppUpdateCheck.UpToDate else {
            val assets = release.assets.filter { it.name == manifest.apkName }
            if (assets.size != 1) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
            val apk = assets.single()
            requireAsset(apk)
            if (apk.size !in 1..VpnAppUpdatePolicy.MAX_APK_BYTES) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
            VpnAppUpdateCheck.Available(VpnAppRelease(manifest, apk.browserDownloadUrl, apk.size, release.htmlUrl))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: NoPublishedRelease) {
        VpnAppUpdateCheck.NoRelease
    } catch (failure: VpnAppUpdateFailure) {
        VpnAppUpdateCheck.Failed(failure.kind)
    } catch (_: SerializationException) {
        VpnAppUpdateCheck.Failed(VpnAppUpdateError.METADATA)
    } catch (_: IOException) {
        VpnAppUpdateCheck.Failed(VpnAppUpdateError.NETWORK)
    } catch (_: IllegalArgumentException) {
        VpnAppUpdateCheck.Failed(VpnAppUpdateError.METADATA)
    }

    override suspend fun download(release: VpnAppRelease, target: File, progress: (Long, Long) -> Unit) {
        val completed = withTimeoutOrNull(360_000) { downloadFile(release, target, progress); true }
        if (completed != true) throw VpnAppUpdateFailure(VpnAppUpdateError.NETWORK)
    }

    private suspend fun downloadFile(release: VpnAppRelease, target: File, progress: (Long, Long) -> Unit) {
        if (!release.manifest.isValid || release.sizeBytes !in 1..VpnAppUpdatePolicy.MAX_APK_BYTES ||
            !VpnAppUpdatePolicy.releaseAssetUrl(release.downloadUrl)) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        try { fetch(release.downloadUrl, api = false, timeoutSeconds = 300) { response, active ->
            if (!active()) throw CancellationException()
            val length = response.body.contentLength()
            if (length >= 0 && length != release.sizeBytes) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            try { FileOutputStream(target).use { output ->
                target.makePrivateFile()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (active()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (count + read > release.sizeBytes) throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        count += read
                        progress(count, release.sizeBytes)
                    }
                }
                if (!active()) throw CancellationException()
                if (count != release.sizeBytes || digest.digest().hexDigest() != release.manifest.apkSha256.lowercase()) {
                    throw VpnAppUpdateFailure(VpnAppUpdateError.VERIFICATION)
                }
                output.fd.sync()
            } } finally {
                // Cancellation may finish the caller before this callback has opened its file.
                if (!active()) target.delete()
            }
        } } catch (failure: Exception) { target.delete(); throw failure }
    }

    private fun requireAsset(asset: GithubAsset) {
        if (!VpnAppUpdatePolicy.releaseAssetUrl(asset.browserDownloadUrl) ||
            asset.browserDownloadUrl.toHttpUrl().pathSegments.last() != asset.name) {
            throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        }
    }

    private suspend fun text(url: String, api: Boolean = false): String = fetch(url, api) { response, active ->
        if (response.body.contentLength() > MAX_METADATA_BYTES) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
        val bytes = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (active()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (bytes.size() + read > MAX_METADATA_BYTES) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
                bytes.write(buffer, 0, read)
            }
        }
        if (!active()) throw CancellationException()
        bytes.toString(Charsets.UTF_8.name())
    }

    private suspend fun <T> fetch(initialUrl: String, api: Boolean, timeoutSeconds: Long = 45, read: (Response, () -> Boolean) -> T): T {
        var url = initialUrl.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            if (api && url.toString() != VpnAppUpdatePolicy.RELEASES_API ||
                !api && !VpnAppUpdatePolicy.downloadHopAllowed(url.toString())) throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
            when (val fetched = request(url, api, timeoutSeconds, read)) {
                is Fetched.Value -> return fetched.value
                is Fetched.Redirect -> url = fetched.url
            }
        }
        throw VpnAppUpdateFailure(VpnAppUpdateError.NETWORK)
    }

    private suspend fun <T> request(url: HttpUrl, api: Boolean, timeoutSeconds: Long, read: (Response, () -> Boolean) -> T): Fetched<T> =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(Request.Builder().url(url).get().header("Accept", if (api) "application/vnd.github+json" else "application/octet-stream").build())
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!continuation.isActive) throw CancellationException()
                            when {
                                response.isRedirect -> Fetched.Redirect(url.resolve(response.header("Location").orEmpty())
                                    ?: throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA))
                                api && response.code == 404 -> throw NoPublishedRelease()
                                response.code == 429 || response.code == 403 && response.header("x-ratelimit-remaining") == "0" ->
                                    throw VpnAppUpdateFailure(VpnAppUpdateError.RATE_LIMITED)
                                response.code >= 500 -> throw VpnAppUpdateFailure(VpnAppUpdateError.NETWORK)
                                response.code != 200 -> throw VpnAppUpdateFailure(VpnAppUpdateError.METADATA)
                                else -> Fetched.Value(read(response) { continuation.isActive })
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }

    @Serializable private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GithubAsset>,
    )

    @Serializable private data class GithubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0,
    )

    private sealed interface Fetched<out T> {
        data class Value<T>(val value: T) : Fetched<T>
        data class Redirect(val url: HttpUrl) : Fetched<Nothing>
    }

    private class NoPublishedRelease : Exception()

    private companion object {
        const val MAX_METADATA_BYTES = 512 * 1024
        const val MAX_REDIRECTS = 5
        fun guardedClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS)
            .dns(appUpdateDns(Dns.SYSTEM)).build()
    }
}

internal fun appUpdateDns(delegate: Dns): Dns = Dns { host ->
    val addresses = delegate.lookup(host)
    if (addresses.isEmpty() || addresses.any { it.isPrivateOrLocalAddress() && !it.isTunnelSynthesizedAddress() }) {
        throw UnknownHostException("app update destination refused")
    }
    addresses
}

internal fun ByteArray.hexDigest(): String = joinToString("") { "%02x".format(it) }

internal fun File.makePrivateFile() {
    if (!setReadable(false, false) || !setWritable(false, false) || !setReadable(true, true) || !setWritable(true, true)) {
        throw VpnAppUpdateFailure(VpnAppUpdateError.STORAGE)
    }
}
