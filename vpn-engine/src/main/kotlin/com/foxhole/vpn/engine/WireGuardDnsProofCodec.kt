package com.foxhole.vpn.engine

import java.security.SecureRandom
import java.io.InputStream

internal data class DnsProofChallenge(val id: Int, val labels: List<String>, val query: ByteArray)
internal data class DnsProofReply(val truncated: Boolean, val rcode: Int)

/** One unpredictable question; no DNS answer address or cached name is trusted. */
internal object WireGuardDnsProofCodec {
    const val MAX_TCP_BYTES = 4096
    const val MAX_UDP_BYTES = 512

    fun challenge(random: SecureRandom): DnsProofChallenge {
        val seed = ByteArray(18).also(random::nextBytes)
        val id = ((seed[0].toInt() and 255) shl 8) or (seed[1].toInt() and 255)
        val nonce = seed.drop(2).joinToString("") { "%02x".format(it.toInt() and 255) }
        val labels = listOf("fh-$nonce", "invalid")
        val question = labels.flatMap { listOf(it.length.toByte()) + it.toByteArray(Charsets.US_ASCII).toList() } +
            listOf(0, 0, 1, 0, 1).map(Int::toByte)
        val bytes = ByteArray(12 + question.size)
        bytes[0] = (id ushr 8).toByte(); bytes[1] = id.toByte()
        bytes[2] = 1; bytes[5] = 1 // RD, one IN A question; no EDNS/DO.
        question.forEachIndexed { index, value -> bytes[12 + index] = value }
        return DnsProofChallenge(id, labels, bytes)
    }

    fun inspect(challenge: DnsProofChallenge, bytes: ByteArray, tcp: Boolean): DnsProofReply {
        require(bytes.size in 12..(if (tcp) MAX_TCP_BYTES else MAX_UDP_BYTES)) { "DNS proof size" }
        val reader = Reader(bytes)
        try {
            require(reader.u16(0) == challenge.id) { "DNS proof transaction" }
            val flags = reader.u16(2)
            require(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0040 == 0) { "DNS proof response flags" }
            val rcode = flags and 15
            require(rcode == 0 || rcode == 3) { "DNS proof error response" }
            val truncated = flags and 0x0200 != 0
            require(!tcp || !truncated) { "Truncated TCP DNS proof" }
            require(reader.u16(4) == 1) { "DNS proof question count" }
            val question = reader.name(12)
            require(question.first == challenge.labels && reader.u16(question.second) == 1 && reader.u16(question.second + 2) == 1) {
                "DNS proof question mismatch"
            }
            val counts = listOf(reader.u16(6), reader.u16(8), reader.u16(10))
            try {
                var cursor = question.second + 4
                var opt = false
                counts.forEachIndexed { section, count ->
                    repeat(count) {
                        cursor = reader.name(cursor).second
                        val type = reader.u16(cursor)
                        val length = reader.u16(cursor + 8)
                        reader.available(cursor, 10 + length)
                        if (type == 41) {
                            require(section == 2 && !opt && reader.u16(cursor + 4) == 0) { "DNS proof extended error/version" }
                            opt = true
                        }
                        cursor += 10 + length
                    }
                }
                require(cursor == bytes.size) { "DNS proof trailing data" }
            } catch (incomplete: Incomplete) {
                // A clipped ordinary UDP TC answer only requests a same-peer TCP retry.
                // An incomplete additional section cannot prove its extended RCODE.
                require(truncated && !tcp && counts[2] == 0) { "Incomplete DNS proof" }
            }
            return DnsProofReply(truncated, rcode)
        } catch (_: Incomplete) {
            throw IllegalArgumentException("Incomplete DNS proof header/question")
        }
    }

    fun tcpQuery(query: ByteArray): ByteArray {
        require(query.size in 12..MAX_TCP_BYTES - 2)
        return byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()) + query
    }

    fun tcpFrame(input: InputStream, beforeRead: () -> Unit): ByteArray {
        fun exact(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var position = 0
            while (position < size) {
                beforeRead()
                val count = input.read(bytes, position, size - position)
                require(count > 0) { "Truncated TCP DNS proof frame" }
                position += count
            }
            return bytes
        }
        val prefix = exact(2)
        val size = ((prefix[0].toInt() and 255) shl 8) or (prefix[1].toInt() and 255)
        require(size in 12..MAX_TCP_BYTES) { "TCP DNS proof length" }
        return exact(size)
    }

    private class Incomplete : RuntimeException()

    private class Reader(private val bytes: ByteArray) {
        fun available(offset: Int, count: Int) {
            require(offset >= 0 && count >= 0)
            if (offset > bytes.size - count) throw Incomplete()
        }
        fun u16(offset: Int): Int {
            available(offset, 2)
            return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        }
        fun name(start: Int): Pair<List<String>, Int> {
            var cursor = start
            var end: Int? = null
            var expanded = 1
            val labels = mutableListOf<String>()
            var hops = 0
            while (true) {
                require(++hops <= 128) { "DNS proof compression loop" }
                available(cursor, 1)
                val length = bytes[cursor].toInt() and 255
                if (length and 0xc0 == 0xc0) {
                    val pointer = u16(cursor) and 0x3fff
                    require(pointer < cursor) { "DNS proof forward/cyclic compression" }
                    if (end == null) end = cursor + 2
                    cursor = pointer
                    continue
                }
                require(length and 0xc0 == 0 && length <= 63) { "DNS proof label" }
                cursor++
                if (length == 0) return labels to (end ?: cursor)
                available(cursor, length)
                expanded += length + 1
                require(expanded <= 255) { "DNS proof name length" }
                val label = bytes.copyOfRange(cursor, cursor + length)
                require(label.all { (it.toInt() and 255) in 0x21..0x7e }) { "DNS proof label encoding" }
                labels += label.toString(Charsets.US_ASCII).lowercase(java.util.Locale.ROOT)
                cursor += length
            }
        }
    }
}
