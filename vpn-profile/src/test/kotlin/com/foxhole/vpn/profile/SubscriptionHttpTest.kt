package com.foxhole.vpn.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionHttpTest {
    @Test fun cancellingBeforeResponseCancelsTheOwnedCall() = runTest {
        val call = OwnedCall()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { call.awaitSubscription() }
        job.cancelAndJoin()
        assertTrue(call.cancelled)
        val body = RecordingBody("late response")
        call.respond(body)
        assertTrue(body.closed)
        assertFalse(body.readStarted)
    }

    @Test fun boundedBodyAndProviderTitleAreDeliveredAfterBodyCloses() = runTest {
        val call = OwnedCall()
        val result = async(UnconfinedTestDispatcher(testScheduler)) { call.awaitSubscription() }
        val body = RecordingBody("fixture-profile")
        call.respond(body, title = "base64:Rml4dHVyZSBwbGFu", userInfo = "expire=1779563665")
        val content = requireNotNull(result.await().content)
        assertEquals("fixture-profile", content.body)
        assertEquals("Fixture plan", content.displayName)
        assertEquals(1779563665000L, content.subscriptionExpiresAt)
        assertTrue(body.closed)
        assertFalse(content.toString().contains("fixture-profile"))
    }

    @Test fun redirectClosesBodyWithoutReadingAndDoesNotExposeItInText() = runTest {
        val call = OwnedCall()
        val result = async(UnconfinedTestDispatcher(testScheduler)) { call.awaitSubscription() }
        val body = RecordingBody("unused")
        call.respond(body, code = 302, redirect = "https://1.1.1.1/fixture")
        val reply = result.await()
        assertEquals("https://1.1.1.1/fixture", reply.redirect)
        assertTrue(body.closed)
        assertFalse(body.readStarted)
        assertFalse(reply.toString().contains("/fixture"))
    }

    @Test fun rejectedAndOversizeBodiesAlwaysClose() {
        listOf(500 to 1L, 200 to (MAX_SUBSCRIPTION_BYTES + 1L), 200 to -1L).forEach { (code, length) ->
            val call = OwnedCall()
            val text = if (length < 0) "x".repeat(MAX_SUBSCRIPTION_BYTES + 1) else "fixture"
            val body = RecordingBody(text, length)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    val result = async(Dispatchers.Unconfined) { call.awaitSubscription() }
                    call.respond(body, code = code)
                    result.await()
                }
            }
            assertTrue(body.closed)
        }
    }

    @Test(timeout = 5000)
    fun cancellingWhileBodyReadIsBlockedCancelsAndClosesWithoutLateResult() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val body = BlockingBody(entered, released)
        val call = OwnedCall(onCancel = { released.countDown() })
        val result = async(Dispatchers.IO) { call.awaitSubscription() }
        assertTrue(withContext(Dispatchers.IO) { call.enqueued.await(2, TimeUnit.SECONDS) })
        val worker = thread(name = "subscription-test-body") { call.respond(body) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
        result.cancelAndJoin()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(call.cancelled)
        assertTrue(body.closed)
        assertTrue(result.isCancelled)
    }

    private class OwnedCall(private val onCancel: () -> Unit = {}) : Call by delegateCall() {
        @Volatile var callback: Callback? = null

        @Volatile var cancelled = false
        val enqueued = CountDownLatch(1)
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued.countDown()
        }
        override fun cancel() {
            cancelled = true
            onCancel()
        }
        fun respond(
            body: ResponseBody,
            code: Int = 200,
            title: String? = null,
            redirect: String? = null,
            userInfo: String? = null
        ) {
            val response = Response.Builder().request(
                request()
            ).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body)
            title?.let { response.header("profile-title", it) }
            redirect?.let { response.header("Location", it) }
            userInfo?.let { response.header("subscription-userinfo", it) }
            requireNotNull(callback).onResponse(this, response.build())
        }
    }

    private class RecordingBody(text: String, private val length: Long = text.length.toLong()) : ResponseBody() {
        var closed = false
        var readStarted = false
        private val stream = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted = true
                return super.read(sink, byteCount)
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = stream
    }

    private class BlockingBody(entered: CountDownLatch, released: CountDownLatch) : ResponseBody() {
        @Volatile var closed = false
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                if (!released.await(3, TimeUnit.SECONDS)) throw IOException("fixture timeout")
                return -1
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = stream
    }

    private companion object {
        fun delegateCall(): Call = OkHttpClient().newCall(Request.Builder().url("https://1.1.1.1/fixture").build())
    }
}
