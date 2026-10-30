package com.foxhole.vpn.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

internal data class FingerprintBundle(val manifest: ByteArray, val signature: ByteArray, val document: ByteArray)
internal data class FingerprintManifestBytes(val manifest: ByteArray, val signature: ByteArray)
internal data class VerifiedFingerprintBundle(
    val bundle: FingerprintBundle,
    val manifest: FingerprintManifest,
    val profileDigests: Map<String, String>,
)

internal data class FingerprintManifest(val generatedAt: Instant, val sha256: String, val size: Long, val profileCount: Int) {
    fun requireNotOlderThan(installed: FingerprintManifest?) {
        if (installed == null) return
        require(!generatedAt.isBefore(installed.generatedAt)) { "fingerprint rollback" }
        require(generatedAt != installed.generatedAt || sha256 == installed.sha256) { "fingerprint timestamp conflict" }
    }
}

internal class FingerprintFeedVerifier(
    private val currentVersion: String,
    private val publicKeyDer: ByteArray = Base64.getDecoder().decode(PUBLIC_KEY_DER),
    private val knownProfiles: Set<String> = FingerprintBaseline.digests.keys,
) {
    fun manifest(raw: ByteArray, signature: ByteArray, now: Instant, fresh: Boolean = true): FingerprintManifest {
        require(raw.size in 1..MAX_MANIFEST_BYTES && signature.size in 1..MAX_SIGNATURE_BYTES) { "fingerprint metadata bounds" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(raw); verify(signature) }) {
            "fingerprint signature refused"
        }
        val root = objectFrom(raw)
        root.requireFields(setOf("schema", "name", "format", "generated_at", "source", "artifact", "compatibility"))
        require(root.integer("schema") == 1 && root.text("name") == "foxhole-tls-fingerprints" &&
            root.text("format") == "tls-fingerprint-tables-json") { "fingerprint manifest identity" }
        val generated = Instant.parse(root.text("generated_at"))
        if (fresh) {
            require(!generated.isAfter(now.plusSeconds(86400)) && !generated.isBefore(now.minusSeconds(45L * 86400))) {
                "fingerprint manifest freshness"
            }
        }
        val source = root.obj("source")
        source.requireFields(setOf("repo", "dataset", "license", "profile_count"), setOf("upstream_reference"))
        require(source.text("repo") == "https://github.com/foxhole-team/foxhole-core" && source.text("dataset") == "fingerprints" &&
            source.text("license") == "GPL-3.0-or-later") { "fingerprint provenance" }
        val count = source.integer("profile_count")
        require(count in 4..128) { "fingerprint profile count" }
        val artifact = root.obj("artifact")
        artifact.requireFields(setOf("file", "size", "sha256"))
        require(artifact.text("file") == "fingerprints.json") { "fingerprint artifact identity" }
        val size = artifact.number("size")
        require(size in 2..MAX_DOCUMENT_BYTES.toLong()) { "fingerprint artifact bounds" }
        val digest = artifact.text("sha256")
        require(digest.matches(SHA256)) { "fingerprint artifact digest" }
        val compatibility = root.obj("compatibility")
        compatibility.requireFields(setOf("min_app_version"))
        require(compareVersions(currentVersion, compatibility.text("min_app_version")) >= 0) { "fingerprint app version" }
        return FingerprintManifest(generated, digest, size, count)
    }

    fun verify(bundle: FingerprintBundle, now: Instant, fresh: Boolean = true): VerifiedFingerprintBundle {
        val manifest = manifest(bundle.manifest, bundle.signature, now, fresh)
        require(bundle.document.size.toLong() == manifest.size && sha256(bundle.document) == manifest.sha256) {
            "fingerprint artifact mismatch"
        }
        val root = objectFrom(bundle.document)
        root.requireFields(setOf("schema", "generated_at", "profiles"))
        require(root.integer("schema") == 1 && Instant.parse(root.text("generated_at")) == manifest.generatedAt) {
            "fingerprint table identity"
        }
        val profiles = root["profiles"] as? JsonArray ?: error("fingerprint profiles missing")
        require(profiles.size == manifest.profileCount) { "fingerprint profile count mismatch" }
        val digests = linkedMapOf<String, String>()
        for (element in profiles) {
            val profile = element as? JsonObject ?: error("fingerprint profile invalid")
            val name = profile.text("name")
            require(name in knownProfiles && name !in digests) { "fingerprint profile unsupported or duplicate" }
            require(profile.integer("schema") == 1 && profile.text("describes").length in 1..512) { "fingerprint profile identity" }
            val fingerprint = profile.obj("fingerprint")
            val digest = profile.text("fingerprint_sha256")
            require(digest.matches(SHA256) && sha256(canonical(fingerprint).toByteArray(Charsets.US_ASCII)) == digest) {
                "fingerprint profile digest mismatch"
            }
            digests[name] = digest
        }
        return VerifiedFingerprintBundle(bundle, manifest, digests)
    }

    private fun objectFrom(bytes: ByteArray): JsonObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) { "fingerprint JSON depth" } }
                '}', ']' -> depth--
            }
        }
        return Json.parseToJsonElement(text) as? JsonObject ?: error("fingerprint JSON object required")
    }

    private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: error("fingerprint object missing")
    private fun JsonObject.text(name: String): String {
        val value = this[name] as? JsonPrimitive ?: error("fingerprint string missing")
        require(value.isString) { "fingerprint string invalid" }
        return value.content
    }
    private fun JsonObject.integer(name: String): Int {
        val value = this[name] as? JsonPrimitive ?: error("fingerprint integer missing")
        require(!value.isString) { "fingerprint integer invalid" }
        return requireNotNull(value.intOrNull) { "fingerprint integer invalid" }
    }
    private fun JsonObject.number(name: String): Long {
        val value = this[name] as? JsonPrimitive ?: error("fingerprint number missing")
        require(!value.isString) { "fingerprint number invalid" }
        return requireNotNull(value.longOrNull) { "fingerprint number invalid" }
    }
    private fun JsonObject.requireFields(required: Set<String>, optional: Set<String> = emptySet()) {
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "fingerprint fields unsupported" }
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Int> {
            require(value.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) { "fingerprint version invalid" }
            return value.split('.').map { requireNotNull(it.toIntOrNull()) }
        }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private companion object {
        const val PUBLIC_KEY_DER = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUucWYOJ+RmNoGzlv6lyQ7TdvK1Op6TRMy+ADsghRHXfKh8gIytQMTq0hKq7TB1GRmVeyysYX3kpmuGGz1buayA=="
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 64 * 1024
internal const val MAX_SIGNATURE_BYTES = 72

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${quoted(it.key)}:${canonical(it.value)}" }
    is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
    is JsonPrimitive -> if (value.isString) quoted(value.content) else value.content
}

private fun quoted(value: String): String = buildString {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        else -> if (character < ' ' || character > '~') append("\\u%04x".format(character.code)) else append(character)
    }
    append('"')
}
