package com.foxhole.vpn.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FingerprintBaselineTest {
    @Test fun baselineRequiresRegenerationWhenTheCorePinChanges() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "config/foxcore-revision.txt").isFile }
        assertEquals(File(root, "config/foxcore-revision.txt").readText().trim(), FingerprintBaseline.baselineSourceRevision)
        assertEquals(9, FingerprintBaseline.digests.size)
        assertTrue(FingerprintBaseline.digests.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        val receipt = Json.parseToJsonElement(File(root, "config/foxcore-source-manifest.json").readText()).jsonObject
        assertEquals(FingerprintBaseline.baselineSourceRevision, receipt.getValue("commit").jsonPrimitive.content)
        val files = receipt.getValue("files").jsonArray.map { it.jsonObject }
            .filter { it.getValue("path").jsonPrimitive.content.startsWith("fingerprints/") }
            .associate { it.getValue("path").jsonPrimitive.content to it.getValue("sha256").jsonPrimitive.content }
        assertEquals(FingerprintBaseline.digests.keys, files.keys.map { File(it).nameWithoutExtension }.toSet())
        val source = listOfNotNull(System.getenv("FOXCORE_SOURCE_ROOT")?.let { File(it, "fingerprints") },
            File(root.parentFile, "foxhole-core_dev/fingerprints"), File(root.parentFile, "foxhole-core/fingerprints"))
            .firstOrNull { it.isDirectory }
        if (source == null) {
            println("Fingerprint baseline: pin, nine names and source receipt checked; Core source unavailable for payload comparison")
            return
        }
        val expected = source.listFiles()!!.filter { it.extension == "json" }.associate { file ->
            assertEquals(files.getValue("fingerprints/${file.name}"), FingerprintFeedFixture.digest(file.readBytes()))
            val profile = Json.parseToJsonElement(file.readText()).jsonObject
            profile.getValue("name").jsonPrimitive.content to profile.getValue("fingerprint_sha256").jsonPrimitive.content
        }
        assertEquals(expected, FingerprintBaseline.digests)
        println("Fingerprint baseline: all nine source files and profile digests checked")
    }
}
