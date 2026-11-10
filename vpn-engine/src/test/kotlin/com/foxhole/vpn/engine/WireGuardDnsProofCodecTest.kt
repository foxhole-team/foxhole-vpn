package com.foxhole.vpn.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.SecureRandom

internal fun dnsProofTestReply(query: ByteArray, tc: Boolean = false, rcode: Int = 3): ByteArray = query.copyOf().also {
    it[2] = (0x81 or if (tc) 2 else 0).toByte()
    it[3] = (0x80 or rcode).toByte()
}

class WireGuardDnsProofCodecTest {
    private fun challenge() = WireGuardDnsProofCodec.challenge(SecureRandom())

    @Test fun nonceQuestionAndNoerrorOrNxdomainRepliesMatchStrictly() {
        val first = challenge(); val second = challenge()
        assertEquals(35, first.labels.first().length)
        assertEquals("invalid", first.labels.last())
        assertFalse(first.query.contentEquals(second.query))
        for (rcode in listOf(0, 3)) {
            val reply = WireGuardDnsProofCodec.inspect(first, dnsProofTestReply(first.query, rcode = rcode), false)
            assertFalse(reply.truncated); assertEquals(rcode, reply.rcode)
        }
    }

    @Test fun udpTcIsRetryOnlyAndTcpTcOrErrorNeverSucceeds() {
        val request = challenge()
        assertTrue(WireGuardDnsProofCodec.inspect(request, dnsProofTestReply(request.query, tc = true), false).truncated)
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardDnsProofCodec.inspect(request, dnsProofTestReply(request.query, tc = true), true)
        }
        for (rcode in listOf(1, 2, 5, 15)) {
            assertThrows(IllegalArgumentException::class.java) {
                WireGuardDnsProofCodec.inspect(request, dnsProofTestReply(request.query, tc = true, rcode = rcode), false)
            }
        }
    }

    @Test fun wrongIdQuestionFlagsAndMalformedSectionsAreRejected() {
        val request = challenge()
        val valid = dnsProofTestReply(request.query)
        val cases = listOf(
            valid.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            valid.copyOf().also { it[16] = 'z'.code.toByte() },
            valid.copyOf().also { it[2] = 1 },
            valid.copyOf().also { it[5] = 2 },
            valid.copyOf().also { it[7] = 1 },
            valid + byteArrayOf(0), valid.copyOf(15), ByteArray(4097),
            valid.copyOf().also { it[12] = 0xc0.toByte(); it[13] = 12 },
        )
        for (reply in cases) assertThrows(IllegalArgumentException::class.java) {
            WireGuardDnsProofCodec.inspect(request, reply, false)
        }
    }

    @Test fun tcpLengthPrefixIsBoundedAndPartialFramesAreRejected() {
        val request = challenge()
        val body = dnsProofTestReply(request.query)
        val frame = WireGuardDnsProofCodec.tcpQuery(body)
        assertArrayEquals(body, WireGuardDnsProofCodec.tcpFrame(ByteArrayInputStream(frame)) {})
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 11), byteArrayOf(0x10, 1), frame.dropLast(1).toByteArray())) {
            assertThrows(IllegalArgumentException::class.java) {
                WireGuardDnsProofCodec.tcpFrame(ByteArrayInputStream(bytes)) {}
            }
        }
    }
}
