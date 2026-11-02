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

class SubscriptionFetcher internal constructor(
    private val client: OkHttpClient,
    private val userAgent: String,
) {
    constructor(userAgent: String = "FoxHole/VPN") : this(subscriptionHttpClient(), userAgent)

    suspend fun fetch(source: String): String = fetchWithMetadata(source).body

    suspend fun fetchWithMetadata(source: String): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val sessionClient = client.newBuilder().cookieJar(SubscriptionSessionCookieJar()).build()
        var url = source.ensurePublicSubscriptionUrl()
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            require(url.username.isEmpty() && url.password.isEmpty())
            val request = Request.Builder().url(url).get().header("User-Agent", userAgent).build()
            val reply = sessionClient.newCall(request).awaitSubscription()
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

private fun subscriptionHttpClient(): OkHttpClient = OkHttpClient.Builder()
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
