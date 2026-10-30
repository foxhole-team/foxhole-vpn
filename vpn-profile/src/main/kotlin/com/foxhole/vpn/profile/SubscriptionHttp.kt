package com.foxhole.vpn.profile

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_SUBSCRIPTION_BYTES = 1024 * 1024

internal suspend fun Call.awaitSubscription(): SubscriptionHttpReply = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    val callback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val reply = response.use {
                    if (!continuation.isActive) return
                    if (response.code in REDIRECT_CODES) {
                        SubscriptionHttpReply(redirect = requireNotNull(response.header("Location")))
                    } else {
                        require(response.isSuccessful)
                        require(response.body.contentLength() <= MAX_SUBSCRIPTION_BYTES)
                        val output = ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (continuation.isActive) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= MAX_SUBSCRIPTION_BYTES)
                                output.write(buffer, 0, count)
                            }
                        }
                        if (!continuation.isActive) return
                        SubscriptionHttpReply(
                            content = SubscriptionFetchResult(
                                output.toString(Charsets.UTF_8.name()),
                                subscriptionDisplayName(response.header("profile-title")),
                                subscriptionExpiry(response.header("subscription-userinfo")),
                            )
                        )
                    }
                }
                if (continuation.isActive) continuation.resume(reply)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
    try {
        enqueue(callback)
    } catch (error: Exception) {
        cancel()
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
