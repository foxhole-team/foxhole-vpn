package com.foxhole.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException

class ProfileInputTest {
    @Test
    fun utf8InputPreservesTheProfileText() {
        val source = "vless://owned@example.invalid#Owned"
        assertEquals(source, readBoundedProfileText(ByteArrayInputStream(source.toByteArray())))
    }

    @Test
    fun oneMebibyteIsTheExactInputBoundary() {
        val source = "a".repeat(1024 * 1024)
        assertEquals(source, readBoundedProfileText(ByteArrayInputStream(source.toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedProfileText(ByteArrayInputStream((source + "a").toByteArray()))
        }
    }

    @Test
    fun malformedUtf8IsRejectedInsteadOfRewritingCredentials() {
        assertThrows(CharacterCodingException::class.java) {
            readBoundedProfileText(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
    }
}
