package com.foxhole.vpn.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardDnsProofLifecycleTest {
    private class Connection(private val respond: suspend (ByteArray) -> ByteArray) : DnsProofConnection {
        var closes = 0
        var finished = false
        val queries = mutableListOf<ByteArray>()
        val deadlines = mutableListOf<Long>()
        override suspend fun exchange(query: ByteArray, deadlineNanos: Long): ByteArray {
            queries += query.copyOf(); deadlines += deadlineNanos
            try { return respond(query) } finally { finished = true }
        }
        override fun close() { closes++ }
    }

    @Test fun udpSuccessClosesOnlyItsOwnConnection() = runTest {
        val udp = Connection { dnsProofTestReply(it) }
        val modes = mutableListOf<DnsProofTransport>()
        val proof = WireGuardDnsProof({ mode -> modes += mode; udp }, dispatcher = StandardTestDispatcher(testScheduler))
        proof.validate(); proof.closeAndJoin()
        assertEquals(listOf(DnsProofTransport.UDP), modes)
        assertEquals(1, udp.closes); assertTrue(udp.finished)
    }

    @Test fun tcRetriesTcpWithSameQuestionAndSharedDeadlineAfterClosingUdp() = runTest {
        val udp = Connection { dnsProofTestReply(it, tc = true) }
        val tcp = Connection { dnsProofTestReply(it) }
        val modes = mutableListOf<DnsProofTransport>()
        val proof = WireGuardDnsProof({ mode ->
            modes += mode
            if (mode == DnsProofTransport.UDP) udp else { assertEquals(1, udp.closes); tcp }
        }, dispatcher = StandardTestDispatcher(testScheduler))
        proof.validate(); proof.closeAndJoin()
        assertEquals(listOf(DnsProofTransport.UDP, DnsProofTransport.TCP), modes)
        assertArrayEquals(udp.queries.single(), tcp.queries.single())
        assertEquals(udp.deadlines.single(), tcp.deadlines.single())
        assertEquals(1, tcp.closes)
    }

    @Test fun wrongIdNeverCreatesTcpFallbackAndClosesUdp() = runTest {
        val udp = Connection { query -> dnsProofTestReply(query, tc = true).also { it[0] = (it[0].toInt() xor 1).toByte() } }
        val modes = mutableListOf<DnsProofTransport>()
        val proof = WireGuardDnsProof({ mode -> modes += mode; udp }, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(ValidationFailure.DNS, (runCatching { proof.validate() }.exceptionOrNull() as? ValidationException)?.failure)
        proof.closeAndJoin()
        assertEquals(listOf(DnsProofTransport.UDP), modes); assertEquals(1, udp.closes)
    }

    @Test fun truncatedOrErrorTcpCannotPassAndBothConnectionsClose() = runTest {
        for (tc in listOf(true, false)) {
            val udp = Connection { dnsProofTestReply(it, tc = true) }
            val tcp = Connection { dnsProofTestReply(it, tc = tc, rcode = if (tc) 3 else 2) }
            val proof = WireGuardDnsProof({ mode -> if (mode == DnsProofTransport.UDP) udp else tcp },
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(ValidationFailure.DNS, (runCatching { proof.validate() }.exceptionOrNull() as? ValidationException)?.failure)
            proof.closeAndJoin()
            assertEquals(1, udp.closes); assertEquals(1, tcp.closes)
        }
    }

    @Test fun timeoutClosesAndJoinsTheOwnedExchange() = runTest {
        val response = CompletableDeferred<ByteArray>()
        val udp = Connection { response.await() }
        val proof = WireGuardDnsProof({ udp }, dispatcher = StandardTestDispatcher(testScheduler), timeoutMs = 1_000)
        val request = async { proof.validate() }
        runCurrent(); advanceTimeBy(1_001); runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is TimeoutCancellationException)
        proof.closeAndJoin()
        assertEquals(1, udp.closes); assertTrue(udp.finished)
    }

    @Test fun stopDuringTcpClosesItAndWaitsForItsFinally() = runTest {
        val response = CompletableDeferred<ByteArray>()
        val udp = Connection { dnsProofTestReply(it, tc = true) }
        val tcp = Connection { response.await() }
        val proof = WireGuardDnsProof({ mode -> if (mode == DnsProofTransport.UDP) udp else tcp },
            dispatcher = StandardTestDispatcher(testScheduler))
        val request = launch { proof.validate() }
        runCurrent()
        proof.closeAndJoin(); request.join()
        assertEquals(1, udp.closes); assertEquals(1, tcp.closes); assertTrue(tcp.finished)
        assertTrue(runCatching { proof.validate() }.isFailure)
    }

    @Test fun cancellationBeforeIoDispatchNeverCreatesASocket() = runTest {
        var created = 0
        val proof = WireGuardDnsProof({ created++; Connection { dnsProofTestReply(it) } },
            dispatcher = StandardTestDispatcher(testScheduler))
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { proof.validate() }
        request.cancel(); proof.closeAndJoin(); runCurrent(); request.join()
        assertEquals(0, created)
    }

    @Test fun socketCreatedAsStopBeginsIsClosedBeforeAnyExchange() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val connection = Connection { dnsProofTestReply(it) }
        lateinit var proof: WireGuardDnsProof
        proof = WireGuardDnsProof({
            launch(UnconfinedTestDispatcher(testScheduler)) { proof.closeAndJoin(); stopped.complete(Unit) }
            connection
        }, dispatcher = StandardTestDispatcher(testScheduler))
        val request = launch { proof.validate() }
        runCurrent(); stopped.await(); request.join()
        assertTrue(connection.queries.isEmpty()); assertEquals(1, connection.closes)
    }

    @Test fun closeFailureStillCancelsAndJoinsTheOwnedIoFinally() = runTest {
        val response = CompletableDeferred<ByteArray>()
        val ioFinally = CompletableDeferred<Unit>()
        val releaseFinally = CompletableDeferred<Unit>()
        val expected = IllegalStateException("Synthetic socket close failure")
        var closes = 0
        var joined = false
        val connection = object : DnsProofConnection {
            override suspend fun exchange(query: ByteArray, deadlineNanos: Long): ByteArray = try {
                response.await()
            } finally {
                withContext(NonCancellable) {
                    ioFinally.complete(Unit)
                    releaseFinally.await()
                    joined = true
                }
            }
            override fun close() { closes++; throw expected }
        }
        val proof = WireGuardDnsProof({ connection }, dispatcher = StandardTestDispatcher(testScheduler))
        val request = launch { proof.validate() }
        runCurrent()
        val stopping = async { runCatching { proof.closeAndJoin() }.exceptionOrNull() }
        runCurrent()
        assertTrue(ioFinally.isCompleted); assertFalse(stopping.isCompleted)
        releaseFinally.complete(Unit); runCurrent()
        assertSame(expected, stopping.await()); request.join()
        assertTrue(joined); assertEquals(1, closes)
    }
}
