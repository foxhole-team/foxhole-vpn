package com.foxhole.vpn.profile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class SubscriptionFetcherTest {
    @Test fun foxholeIdentityKeepsNegotiatedSubscriptionsAcrossHttpsRedirects() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val payload = Base64.getEncoder().encodeToString("ss://YWVzLTI1Ni1nY206Zml4dHVyZQ@8.8.8.8:443#Fixture".toByteArray())
        val caller = Thread.currentThread()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            assertTrue(Thread.currentThread() !== caller)
            val request = chain.request()
            requests += request
            val final = request.url.host == "1.1.1.1"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (final) 200 else 302).message("fixture")
                .apply { if (!final) header("Location", "https://1.1.1.1/subscription") }
                .body((if (request.header("User-Agent") == "FoxHole/fixture") payload else "<html>fixture</html>").toResponseBody()).build()
        }.build()
        try {
            val result = SubscriptionFetcher(client, "FoxHole/fixture").fetchWithMetadata("https://8.8.8.8/subscription")
            assertEquals(payload, result.body)
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.header("User-Agent") == "FoxHole/fixture" })
            assertTrue(!result.toString().contains(payload))
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun redirectsStillRefusePlainHttpAndPrivateAddresses() = runBlocking {
        for (location in listOf("http://1.1.1.1/subscription", "https://127.0.0.1/subscription")) {
            var requests = 0
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                    .header("Location", location).body(byteArrayOf().toResponseBody()).build()
            }.build()
            try {
                try {
                    SubscriptionFetcher(client, "FoxHole/fixture").fetch("https://8.8.8.8/subscription")
                    throw AssertionError("unsafe subscription redirect accepted")
                } catch (_: IllegalArgumentException) {
                    assertEquals(1, requests)
                }
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
