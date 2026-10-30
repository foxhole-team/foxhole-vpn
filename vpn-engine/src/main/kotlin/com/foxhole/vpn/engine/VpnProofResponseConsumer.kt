package com.foxhole.vpn.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the response until its bounded consumer has finished, including body reads. */
internal suspend fun <T> Call.consumeProofResponse(read: (Response, () -> Boolean) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    val result = response.use { read(it) { continuation.isActive } }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

internal fun ensureProofConsumerActive(active: () -> Boolean) {
    if (!active()) throw CancellationException()
}

internal fun readProofBody(response: Response, active: () -> Boolean, maximum: Int): ByteArray {
    ensureProofConsumerActive(active)
    if (response.body.contentLength() > maximum) throw EngineException(EngineFailure.GEO)
    val output = java.io.ByteArrayOutputStream()
    response.body.byteStream().use { input ->
        val buffer = ByteArray(4096)
        while (true) {
            ensureProofConsumerActive(active)
            val count = input.read(buffer)
            if (count < 0) break
            ensureProofConsumerActive(active)
            if (output.size() + count > maximum) throw EngineException(EngineFailure.GEO)
            output.write(buffer, 0, count)
        }
    }
    ensureProofConsumerActive(active)
    return output.toByteArray()
}
