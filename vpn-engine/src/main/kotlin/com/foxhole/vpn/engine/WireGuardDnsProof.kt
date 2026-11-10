package com.foxhole.vpn.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

internal enum class DnsProofTransport { UDP, TCP }

internal interface DnsProofConnection : AutoCloseable {
    suspend fun exchange(query: ByteArray, deadlineNanos: Long): ByteArray
}

/** Caller-owned I/O child; closing a frame closes its socket and joins that child. */
internal class WireGuardDnsProof(
    private val open: (DnsProofTransport) -> DnsProofConnection,
    private val random: () -> SecureRandom = ::SecureRandom,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 6_000,
) {
    init { require(timeoutMs in 1L..6_000L) }
    private val lock = Any()
    private val serial = Mutex()
    private var closed = false
    private var active: Attempt? = null

    suspend fun validate() = serial.withLock { coroutineScope {
        val attempt = Attempt()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val operation: Deferred<Unit> = async(dispatcher, start = CoroutineStart.LAZY) {
            val challenge = WireGuardDnsProofCodec.challenge(random())
            suspend fun request(transport: DnsProofTransport): DnsProofReply {
                currentCoroutineContext().ensureActive()
                val connection = open(transport)
                attempt.install(connection)
                try {
                    val response = connection.exchange(challenge.query, deadline)
                    currentCoroutineContext().ensureActive()
                    return WireGuardDnsProofCodec.inspect(challenge, response, transport == DnsProofTransport.TCP)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException(ValidationFailure.DNS)
                } finally { attempt.release(connection) }
            }
            if (request(DnsProofTransport.UDP).truncated) {
                check(!request(DnsProofTransport.TCP).truncated)
            }
        }
        attempt.operation = operation
        try {
            synchronized(lock) {
                if (closed) throw CancellationException("DNS proof closed")
                check(active == null)
                active = attempt
            }
            operation.start()
            withTimeout(timeoutMs) { operation.await() }
        } finally {
            try { attempt.cancel() }
            finally {
                withContext(NonCancellable) { operation.cancelAndJoin() }
                synchronized(lock) { if (active === attempt) active = null }
            }
        }
    } }

    suspend fun closeAndJoin() {
        val attempt = synchronized(lock) { closed = true; active }
        try { attempt?.cancel() }
        finally { withContext(NonCancellable) { attempt?.operation?.join() } }
    }

    private class Attempt {
        private val lock = Any()
        private var cancelled = false
        private var connection: DnsProofConnection? = null
        lateinit var operation: Deferred<Unit>

        fun install(value: DnsProofConnection) {
            val accepted = synchronized(lock) {
                if (cancelled) false else { check(connection == null); connection = value; true }
            }
            if (!accepted) {
                value.close()
                throw CancellationException("DNS proof superseded")
            }
        }
        fun release(value: DnsProofConnection) {
            val owned = synchronized(lock) {
                if (connection !== value) false else { connection = null; true }
            }
            if (owned) value.close()
        }
        fun cancel() {
            val owned = synchronized(lock) { cancelled = true; connection.also { connection = null } }
            try { owned?.close() } finally { operation.cancel() }
        }
    }
}

private fun remainingDnsProofMs(deadlineNanos: Long): Int {
    val left = deadlineNanos - System.nanoTime()
    if (left <= 0) throw SocketTimeoutException("DNS proof deadline")
    return ((left + 999_999) / 1_000_000).coerceIn(1, 3_000).toInt()
}

internal fun udpDnsProofConnection(plan: WireGuardDnsProofPlan, bind: (DatagramSocket) -> Unit): DnsProofConnection =
    object : DnsProofConnection {
        private val socket = DatagramSocket(null as java.net.SocketAddress?)
        override suspend fun exchange(query: ByteArray, deadlineNanos: Long): ByteArray {
            bind(socket) // Only the selected owned VPN network; never VpnService.protect().
            currentCoroutineContext().ensureActive()
            val endpoint = InetSocketAddress(plan.resolver, 53)
            socket.connect(endpoint)
            socket.soTimeout = remainingDnsProofMs(deadlineNanos)
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(WireGuardDnsProofCodec.MAX_UDP_BYTES + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            require(packet.socketAddress == endpoint && packet.length <= WireGuardDnsProofCodec.MAX_UDP_BYTES) {
                "DNS proof peer/size mismatch"
            }
            return buffer.copyOfRange(packet.offset, packet.offset + packet.length)
        }
        override fun close() { socket.close() }
    }

internal fun tcpDnsProofConnection(plan: WireGuardDnsProofPlan, bind: (Socket) -> Unit): DnsProofConnection =
    object : DnsProofConnection {
        private val socket = Socket()
        override suspend fun exchange(query: ByteArray, deadlineNanos: Long): ByteArray {
            bind(socket)
            currentCoroutineContext().ensureActive()
            socket.connect(InetSocketAddress(plan.resolver, 53), remainingDnsProofMs(deadlineNanos))
            val output = socket.getOutputStream()
            output.write(WireGuardDnsProofCodec.tcpQuery(query))
            output.flush()
            return WireGuardDnsProofCodec.tcpFrame(socket.getInputStream()) {
                socket.soTimeout = remainingDnsProofMs(deadlineNanos)
            }
        }
        override fun close() { socket.close() }
    }
