package com.foxhole.vpn.router.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/** Implements Call directly: no OkHttpClient, DNS, socket, or transport fallback exists. */
internal class RouterReviewCalls(
    private val scope: CoroutineScope,
    private val panel: RouterReviewPanel,
    private val delayMs: Long,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        require(request.url.host == "192.168.1.1" && request.url.port == 8443)
        require(request.url.encodedPath == "/cgi-bin/foxhole" && request.method == "POST")
        return ReviewCall(request)
    }

    private inner class ReviewCall(
        private val original: Request,
        initialTags: Map<Class<*>, Any> = emptyMap(),
    ) : Call {
        private val executed = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private val terminal = AtomicBoolean()
        private val callTimeout = Timeout()
        private val tagLock = Any()
        private val tags = initialTags.toMutableMap()
        private val listeners = CopyOnWriteArrayList<EventListener>()
        @Volatile private var work: Job? = null

        override fun request(): Request = original
        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = callTimeout
        override fun clone(): Call = synchronized(tagLock) { ReviewCall(original, tags.toMap()) }

        override fun addEventListener(eventListener: EventListener) {
            listeners.add(eventListener)
        }

        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.javaObjectType)

        override fun <T> tag(type: Class<out T>): T? = synchronized(tagLock) {
            tags[type]?.let { type.cast(it) }
        }

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            tag(type.javaObjectType, computeIfAbsent)

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = synchronized(tagLock) {
            val existing = tags[type]
            if (existing != null) {
                requireNotNull(type.cast(existing))
            } else {
                val computed = requireNotNull(type.cast(computeIfAbsent()))
                tags[type] = computed
                computed
            }
        }

        override fun execute(): Response = throw IOException("offline_demo_async_only")

        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true))
            listeners.forEach { it.callStart(this) }
            work = scope.launch {
                try {
                    delay(delayMs)
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw CancellationException("offline_demo_cancelled")
                    val buffer = Buffer()
                    requireNotNull(original.body).writeTo(buffer)
                    val bytes = buffer.readByteArray()
                    val result = try {
                        require(bytes.size <= 300000)
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        val action = request["action"]?.toString()?.removeSurrounding("\"")
                            ?: throw IOException("offline_demo_action")
                        val payload = request["payload"] as? JsonObject
                            ?: throw IOException("offline_demo_payload")
                        currentCoroutineContext().ensureActive()
                        panel.reply(action, payload)
                    } finally {
                        bytes.fill(0)
                    }
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw CancellationException("offline_demo_cancelled")
                    responseCallback.onResponse(
                        this@ReviewCall,
                        Response.Builder()
                            .request(original)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Offline simulation")
                            .body(result.toString().toResponseBody("application/json".toMediaType()))
                            .build(),
                    )
                    if (terminal.compareAndSet(false, true)) listeners.forEach { it.callEnd(this@ReviewCall) }
                } catch (_: CancellationException) {
                    fail(responseCallback, "offline_demo_cancelled")
                } catch (_: Exception) {
                    fail(responseCallback, "offline_demo_request_failed")
                }
            }
            if (cancelled.get()) work?.cancel()
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) listeners.forEach { it.canceled(this) }
            work?.cancel()
        }

        private fun fail(callback: Callback, code: String) {
            if (!terminal.compareAndSet(false, true)) return
            val failure = IOException(code)
            listeners.forEach { it.callFailed(this, failure) }
            callback.onFailure(this, failure)
        }
    }
}
