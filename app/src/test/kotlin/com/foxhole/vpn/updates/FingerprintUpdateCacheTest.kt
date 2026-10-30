package com.foxhole.vpn.updates

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class FingerprintUpdateCacheTest {
    @Test fun oneAtomicEnvelopeKeepsManifestSignatureAndArtifactTogether() {
        val directory = Files.createTempDirectory("vpn-feed-cache-").toFile()
        try {
            val file = directory.resolve("tables.v1")
            val cache = FileFingerprintUpdateCache(file)
            val first = FingerprintBundle(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
            cache.write(first)
            val next = FingerprintBundle(byteArrayOf(4, 5), byteArrayOf(6), byteArrayOf(7, 8))
            cache.write(next)
            val loaded = cache.read()!!
            assertArrayEquals(next.manifest, loaded.manifest)
            assertArrayEquals(next.signature, loaded.signature)
            assertArrayEquals(next.document, loaded.document)
            assertEquals(listOf("tables.v1"), directory.list()!!.toList())
            file.appendBytes(byteArrayOf(9))
            assertThrows(IllegalArgumentException::class.java) { cache.read() }
        } finally { directory.deleteRecursively() }
    }
}
