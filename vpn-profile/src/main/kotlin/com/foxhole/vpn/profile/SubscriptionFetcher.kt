package com.foxhole.vpn.profile

import com.foxhole.core.importer.SubscriptionMetadataParser
import com.foxhole.core.network.SubscriptionSessionCookieJar
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.ensurePublicSubscriptionUrl
import com.foxhole.core.network.isPrivateOrLocalAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class SubscriptionFetchResult(val body: String, val displayName: String?, val subscriptionExpiresAt: Long? = null) {
    override fun toString(): String = "SubscriptionFetchResult(body=<redacted>, titlePresent=${displayName != null}, expiryPresent=${subscriptionExpiresAt != null})"
}

internal fun subscriptionDisplayName(header: String?): String? =
    header?.takeIf { it.length <= 8192 }?.let { SubscriptionMetadataParser.decodeMetadataHeader(it, 160) }

class SubscriptionFetcher {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .dns(
            Dns { host ->
                InetAddress.getAllByName(host).toList().also { addresses ->
                    if (addresses.isEmpty() || addresses.any { it.isPrivateOrLocalAddress() }) {
                        throw UnknownHostException(
                            "subscription host is not public"
                        )
                    }
                }
            }
        )
        .build()

    suspend fun fetch(source: String): String = fetchWithMetadata(source).body

    suspend fun fetchWithMetadata(source: String): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val sessionClient = client.newBuilder().cookieJar(SubscriptionSessionCookieJar()).build()
        var url = source.ensurePublicSubscriptionUrl()
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            require(url.username.isEmpty() && url.password.isEmpty())
            val reply = sessionClient.newCall(Request.Builder().url(url).get().build()).awaitSubscription()
            val redirect = reply.redirect
            if (redirect != null) {
                require(hop < 5)
                val next = requireNotNull(url.resolve(redirect))
                url = next.toString().ensurePublicHttpsUrl()
            } else {
                val content = requireNotNull(reply.content)
                return@withContext content.copy(
                    subscriptionExpiresAt = content.subscriptionExpiresAt ?: subscriptionExpiry(null, source)
                )
            }
        }
        throw ProfileImportFailure()
    }
}
