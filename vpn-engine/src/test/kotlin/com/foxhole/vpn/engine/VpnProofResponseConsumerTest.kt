package com.foxhole.vpn.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VpnProofResponseConsumerTest {
    @Test fun successfulActualHttpBodyIsConsumedBeforeDelivery() = runBlocking {
        HttpPeer("hello".toByteArray(), false).use { peer ->
            val value = peer.call.consumeProofResponse { response, active -> readProofBody(response, active, 32) }
            assertEquals("hello", value.toString(Charsets.UTF_8))
            assertTrue(peer.headers.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellingAfterHeadersClosesTheOwnedActualBodyBeforePeerRelease() = runBlocking {
        supervisorScope {
            HttpPeer("hello".toByteArray(), true).use { peer ->
                val entered = CountDownLatch(1)
                val operation = async(Dispatchers.IO) {
                    peer.call.consumeProofResponse { response, active ->
                        entered.countDown()
                        readProofBody(response, active, 32)
                    }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                operation.cancel()
                try {
                    // Concrete ownership RED: legacy headers-only continuation cannot cancel its Call here.
                    assertTrue("body-phase cancellation must cancel the actual Call", peer.call.isCanceled())
                    withTimeout(1_000) { operation.join() }
                    assertTrue(operation.isCancelled)
                    assertTrue(peer.eof.await(1, TimeUnit.SECONDS))
                    assertEquals(1L, peer.release.count)
                } finally {
                    peer.release.countDown()
                    peer.close()
                    withTimeout(5_000) { operation.join() }
                }
            }
        }
    }

    @Test fun actualUnknownLengthBodyCannotExceedTheBound() = runBlocking {
        HttpPeer(ByteArray(33) { 7 }, false, declaredLength = false).use { peer ->
            val result = runCatching {
                peer.call.consumeProofResponse { response, active -> readProofBody(response, active, 32) }
            }
            assertEquals(EngineFailure.GEO, (result.exceptionOrNull() as EngineException).reason)
        }
    }

    @Test fun lateResponseAfterCancellationIsClosedWithoutCallingConsumer() = runBlocking {
        val held = HeldCall()
        var read = false
        val operation = async(Dispatchers.IO) { held.consumeProofResponse { _, _ -> read = true; "bad" } }
        assertTrue(held.enqueued.await(1, TimeUnit.SECONDS))
        operation.cancelAndJoin()
        var bodyClosed = false
        val body = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8("late")
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = 4L
            override fun source(): BufferedSource = buffer
            override fun close() { bodyClosed = true; super.close() }
        }
        val response = Response.Builder().request(held.request()).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
        held.callback.get()!!.onResponse(held, response)
        assertFalse(read)
        assertTrue(held.isCanceled())
        assertTrue(bodyClosed)
    }

    @Test fun actual204HeaderOnlyProofDoesNotWaitForABody() = runBlocking {
        HttpPeer(ByteArray(0), false, status = 204).use { peer ->
            val status = peer.call.consumeProofResponse { response, active ->
                ensureProofConsumerActive(active)
                response.code
            }
            assertEquals(204, status)
        }
    }

    private class HttpPeer(
        private val body: ByteArray,
        delayed: Boolean,
        private val declaredLength: Boolean = true,
        private val status: Int = 200,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val socket = AtomicReference<Socket?>()
        val headers = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        val eof = CountDownLatch(1)
        private val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .callTimeout(5, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${listener.localPort}/fixture").build())
        private val worker = thread(name = "proof-http-fixture") {
            try {
                listener.accept().use { accepted ->
                    socket.set(accepted)
                    accepted.soTimeout = 2_000
                    val input = accepted.getInputStream()
                    var last = ""
                    while (!last.endsWith("\r\n\r\n")) {
                        val value = input.read()
                        if (value < 0 || last.length > 8192) throw IOException("fixture_header")
                        last += value.toChar()
                    }
                    val length = if (declaredLength) "Content-Length: ${body.size}\r\n" else ""
                    val response = "HTTP/1.1 $status OK\r\n${length}Connection: close\r\n\r\n"
                    accepted.getOutputStream().write(response.toByteArray())
                    accepted.getOutputStream().flush()
                    headers.countDown()
                    if (release.count > 0) {
                        // Block on real peer EOF, without sending the delayed response body.
                        if (input.read() < 0) eof.countDown()
                        release.await(2, TimeUnit.SECONDS)
                    }
                    if (!accepted.isClosed) {
                        accepted.getOutputStream().write(body)
                        accepted.getOutputStream().flush()
                    }
                }
            } catch (_: IOException) { /* Own peer closes during cancellation cleanup. */ }
        }
        override fun close() {
            release.countDown()
            socket.get()?.close()
            listener.close()
            worker.join(3_000)
            check(!worker.isAlive)
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private class HeldCall(private val value: Request = Request.Builder().url("http://127.0.0.1/fixture").build()) :
        Call by OkHttpClient().newCall(value) {
        val enqueued = CountDownLatch(1)
        val callback = AtomicReference<Callback?>()
        private var cancelled = false
        override fun request() = value
        override fun execute(): Response = error("enqueue required")
        override fun enqueue(callback: Callback) { this.callback.set(callback); enqueued.countDown() }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback.get() != null
        override fun isCanceled() = cancelled
        override fun clone(): Call = HeldCall()
    }
}
