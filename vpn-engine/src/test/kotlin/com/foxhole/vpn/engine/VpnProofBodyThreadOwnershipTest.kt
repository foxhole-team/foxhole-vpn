package com.foxhole.vpn.engine

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VpnProofBodyThreadOwnershipTest {
    @Test fun cancellingReaderNeverClosesResponseOnTheCancellingThreadOrLeaksAHandlerException() = runBlocking {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> errors += failure })
        val call = OwnedReadCall()
        val operation = scope.launch { call.consumeProofResponse { response, active ->
            call.reading.set(true)
            call.entered.countDown()
            check(call.aborted.await(2, TimeUnit.SECONDS))
            call.reading.set(false)
            ensureProofConsumerActive(active)
            response.code
        } }
        try {
            assertTrue(call.entered.await(2, TimeUnit.SECONDS))
            operation.cancel()
            withTimeout(1_000) { operation.join() }
            assertTrue(call.cancelled.get())
            call.allowCallbackClose.countDown()
            assertTrue(call.closed.await(1, TimeUnit.SECONDS))
            call.callbackThread?.join(1_000)
            assertTrue(call.callbackThread?.isAlive == false)
            assertEquals("cancellation must not close a body while the callback reader owns it", 0, call.foreignCloses)
            assertTrue("no CompletionHandlerException may escape cancellation", errors.isEmpty())
        } finally {
            call.cancel()
            call.allowCallbackClose.countDown()
            call.callbackThread?.join(2_000)
            scope.cancel()
            withTimeout(1_000) { operation.join() }
        }
    }

    private class OwnedReadCall(private val requestValue: Request = Request.Builder().url("http://127.0.0.1/fixture").build()) :
        Call by OkHttpClient().newCall(requestValue) {
        val entered = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val allowCallbackClose = CountDownLatch(1)
        val reading = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        @Volatile var callbackThread: Thread? = null
        @Volatile var foreignCloses = 0
        override fun cancel() { cancelled.set(true); aborted.countDown() }
        override fun isCanceled() = cancelled.get()
        override fun enqueue(callback: Callback) {
            callbackThread = thread(name = "owned-proof-callback") {
                val body = object : ResponseBody() {
                    private val buffer = Buffer().writeUtf8("fixture")
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength() = 7L
                    override fun source(): BufferedSource = buffer
                    override fun close() {
                        if (Thread.currentThread() !== callbackThread) {
                            foreignCloses++
                            throw IllegalStateException("fixture_concurrent_read_close")
                        }
                        check(allowCallbackClose.await(2, TimeUnit.SECONDS))
                        closed.countDown()
                        super.close()
                    }
                }
                callback.onResponse(this, Response.Builder().request(requestValue).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build())
            }
        }
    }
}
