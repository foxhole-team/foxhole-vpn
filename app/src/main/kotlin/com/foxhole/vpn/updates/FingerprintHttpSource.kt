package com.foxhole.vpn.updates

import com.foxhole.core.network.isPrivateOrLocalAddress
import com.foxhole.core.network.isTunnelSynthesizedAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface FingerprintUpdateSource {
    suspend fun manifest(): FingerprintManifestBytes
    suspend fun document(expectedBytes: Long, onProgress: (Long) -> Unit): ByteArray
}

internal class FingerprintHttpSource(callFactory: Call.Factory? = null) : FingerprintUpdateSource {
    private val calls: Call.Factory by lazy { callFactory ?: guardedClient() }

    override suspend fun manifest(): FingerprintManifestBytes = FingerprintManifestBytes(
        fetch("$BASE/fingerprint-manifest.json", MAX_MANIFEST_BYTES.toLong()),
        fetch("$BASE/fingerprint-manifest.json.sig", MAX_SIGNATURE_BYTES.toLong()),
    )

    override suspend fun document(expectedBytes: Long, onProgress: (Long) -> Unit): ByteArray {
        require(expectedBytes in 2..MAX_DOCUMENT_BYTES.toLong()) { "fingerprint download bounds" }
        return fetch("$BASE/fingerprints.json", expectedBytes, expectedBytes, onProgress)
    }

    private suspend fun fetch(
        url: String,
        limit: Long,
        expected: Long? = null,
        onProgress: (Long) -> Unit = {},
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (response.code != 200) throw IOException("fingerprint HTTP ${response.code}")
                        val declared = response.body.contentLength()
                        require(declared <= limit && (expected == null || declared < 0 || declared == expected)) {
                            "fingerprint response size"
                        }
                        val output = ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (continuation.isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                require(output.size() + read <= limit) { "fingerprint response bounds" }
                                output.write(buffer, 0, read)
                                onProgress(output.size().toLong())
                            }
                        }
                        if (!continuation.isActive) return
                        require(expected == null || output.size().toLong() == expected) { "fingerprint response truncated" }
                        continuation.resume(output.toByteArray())
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private companion object {
        const val BASE = "https://foxhole-team.github.io/foxhole-db"
        fun guardedClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .dns(fingerprintDns(Dns.SYSTEM)).build()
    }
}

internal fun fingerprintDns(delegate: Dns): Dns = Dns { host ->
    val addresses = delegate.lookup(host)
    if (addresses.isEmpty() || addresses.any { it.isPrivateOrLocalAddress() && !it.isTunnelSynthesizedAddress() }) {
        throw UnknownHostException("fingerprint DNS destination refused")
    }
    addresses
}
