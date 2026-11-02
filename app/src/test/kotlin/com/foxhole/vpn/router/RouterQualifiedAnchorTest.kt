package com.foxhole.vpn.router

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterQualifiedAnchorTest {
    // Actual retained asset bytes test reader integrity only; this is not final R2 qualification.
    private val retained = "{\n  \"schema\": 1,\n  \"baseUrl\": \"https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0\",\n  \"manifestSha256\": \"e1c54ee4f7d9b013d250d0a7eae19dfdd47b5ad511451c674f5585e432127149\"\n}\n".toByteArray(Charsets.UTF_8)
    private val retainedPin = "05ed4d921bf0ff4488fa9084a5b6fa1ae394c625db27c0f7469a5555d61edae6"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rejects(block: () -> Unit) {
        try { block(); throw AssertionError("Invalid anchor accepted") }
        catch (failure: RuntimeException) { assertTrue(failure is IllegalArgumentException || failure is IllegalStateException) }
    }

    @Test fun nullQualificationPinCannotOpenEvenAnExistingOldAsset() {
        var opened = false
        assertNull(readQualifiedVpnRouterReleaseAnchor(null) { opened = true; ByteArrayInputStream(retained) })
        assertTrue(!opened)
    }

    @Test fun matchingActualRetainedFixtureBytesPassIntegrityButStaleHashDoesNot() {
        assertEquals(retainedPin, sha(retained))
        assertNotNull(readQualifiedVpnRouterReleaseAnchor(retainedPin) { ByteArrayInputStream(retained) })
        rejects { readQualifiedVpnRouterReleaseAnchor("0".repeat(64)) { ByteArrayInputStream(retained) } }
        rejects { readQualifiedVpnRouterReleaseAnchor("invalid") { throw AssertionError("Malformed pin must not open asset") } }
        assertNull(readQualifiedVpnRouterReleaseAnchor(retainedPin) { throw FileNotFoundException("synthetic missing asset") })
    }

    @Test fun oversizedOrNonProgressingStreamFailsClosedAtTheBound() {
        var count = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("buffered reader required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(32, offset, offset + length); count += length; return length
            }
        }
        rejects { readQualifiedVpnRouterReleaseAnchor(retainedPin) { stream } }
        assertTrue(count <= 4097)
        rejects { readQualifiedVpnRouterReleaseAnchor(retainedPin) { object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        } } }
    }

    @Test fun matchingRawHashCannotAuthorizeWrongSchemaKeysHostOrManifest() {
        val text = retained.toString(Charsets.UTF_8)
        for (changed in listOf(text.replace("\"schema\": 1", "\"schema\": 2"),
            text.replace("\"schema\": 1", "\"schema\": \"1\""),
            text.replace("https://github.com/", "http://github.com/"),
            text.replace("https://github.com/", "https://example.com/"),
            text.replace("\"manifestSha256\": \"", "\"manifestSha256\": \"not-a-hash-"),
            text.replace("\"schema\": 1", "\"extraTrust\": true, \"schema\": 1"))) {
            assertTrue(changed != text)
            val bytes = changed.toByteArray(Charsets.UTF_8)
            rejects { readQualifiedVpnRouterReleaseAnchor(sha(bytes)) { ByteArrayInputStream(bytes) } }
        }
    }
}
