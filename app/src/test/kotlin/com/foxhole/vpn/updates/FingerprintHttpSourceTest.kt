package com.foxhole.vpn.updates

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintHttpSourceTest {
    @Test fun refusedDnsAnswersUseACheckedLookupFailure() {
        for (addresses in listOf(emptyList(), listOf(InetAddress.getByName("127.0.0.1")))) {
            val dns = fingerprintDns(Dns { addresses })
            assertThrows(UnknownHostException::class.java) { dns.lookup("foxhole-team.github.io") }
        }
        for (address in listOf("8.8.8.8", "198.18.0.1")) {
            val addresses = listOf(InetAddress.getByName(address))
            assertEquals(addresses, fingerprintDns(Dns { addresses }).lookup("foxhole-team.github.io"))
        }
    }

    private class OwnedCall(private val value: Request, private val bytes: ByteArray?, private val status: Int) : Call by OkHttpClient().newCall(value) {
        var cancelled = false
        override fun request() = value
        override fun execute(): Response = error("blocking execution is forbidden")
        override fun enqueue(responseCallback: Callback) {
            bytes?.let {
                responseCallback.onResponse(this, Response.Builder().request(value).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Owned fixture").body(it.toResponseBody()).build())
            }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = OwnedCall(value, bytes, status)
    }

    @Test fun byteProgressComesFromTheBoundedResponseBody() = runTest {
        val progress = mutableListOf<Long>()
        val source = FingerprintHttpSource(Call.Factory { OwnedCall(it, byteArrayOf(1, 2, 3), 200) })
        assertEquals(3, source.document(3, progress::add).size)
        assertEquals(listOf(3L), progress)
    }

    @Test fun cancellationCancelsTheActualOutstandingCall() = runTest {
        lateinit var call: OwnedCall
        val source = FingerprintHttpSource(Call.Factory { OwnedCall(it, null, 200).also { created -> call = created } })
        val job = launch { source.document(3) {} }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(call.cancelled)
        assertTrue(job.isCancelled)
    }

    @Test fun redirectsAndOversizedBodiesAreRefused() = runTest {
        val redirect = FingerprintHttpSource(Call.Factory { OwnedCall(it, byteArrayOf(1, 2), 302) })
        try { redirect.document(2) {}; error("redirect accepted") } catch (_: IOException) { }
        val oversized = FingerprintHttpSource(Call.Factory { OwnedCall(it, ByteArray(65537), 200) })
        try { oversized.manifest(); error("oversized manifest accepted") } catch (_: IllegalArgumentException) { }
    }
}
