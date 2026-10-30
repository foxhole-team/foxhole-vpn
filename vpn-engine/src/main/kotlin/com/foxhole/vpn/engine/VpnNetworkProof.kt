package com.foxhole.vpn.engine

import android.net.Network
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.EventListener
import android.system.ErrnoException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal class VpnNetworkProof(network: Network) {
    private val client = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(Dns { host -> network.getAllByName(host).toList() })
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun validate() {
        var stage = "REQUEST"
        var family = "NONE"
        val started = android.os.SystemClock.elapsedRealtime()
        val listener = object : EventListener() {
            override fun dnsStart(call: Call, domainName: String) { stage = "DNS" }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { stage = "CONNECT" }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                stage = "CONNECT"
                family = if (inetSocketAddress.address is java.net.Inet6Address) "IPv6" else "IPv4"
            }
            override fun secureConnectStart(call: Call) { stage = "TLS" }
            override fun responseHeadersStart(call: Call) { stage = "HTTP" }
        }
        val request = Request.Builder().url("https://www.google.com/generate_204").get().build()
        try {
            // TUN TCP is acknowledged locally before the upstream protocol handshake completes.
            client.newBuilder().eventListener(listener)
                .callTimeout(30, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
                .build().newCall(request).consumeProofResponse { response, active ->
                ensureProofConsumerActive(active)
                if (response.code != 204) throw ValidationException(ValidationFailure.HTTP_STATUS)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            var cause: Throwable? = failure
            var errno: Int? = null
            repeat(6) { val current = cause; if (current is ErrnoException) errno = current.errno; cause = current?.cause }
            android.util.Log.w("FoxHoleVpn", "VPN proof failed: stage=$stage type=${failure.javaClass.simpleName} errno=${errno ?: 0} family=$family elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            throw failure
        }
    }

    suspend fun location(): EngineLocation =
        client.newCall(Request.Builder().url("https://ipwho.is/").get().build()).consumeProofResponse { response, active ->
            ensureProofConsumerActive(active)
            if (!response.isSuccessful || response.body.contentLength() > MAX_GEO_BYTES) throw EngineException(EngineFailure.GEO)
            val bytes = readProofBody(response, active, MAX_GEO_BYTES)
            parseNetworkLocation(bytes.toString(Charsets.UTF_8))
        }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object { const val MAX_GEO_BYTES = 32 * 1024 }
}

suspend fun lookupNetworkLocation(network: Network): EngineLocation {
    val proof = VpnNetworkProof(network)
    return try { proof.location() } finally { proof.close() }
}
