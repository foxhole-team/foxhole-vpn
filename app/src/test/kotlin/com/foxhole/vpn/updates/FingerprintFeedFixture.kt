package com.foxhole.vpn.updates

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

internal class FingerprintFeedFixture {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val now: Instant = Instant.parse("2026-10-08T00:00:00Z")
    val names = listOf("chrome_151", "chrome_133", "chrome_131", "chrome_120")
    val tables = names.associateWith { name -> """{"label":"$name","suite":[4865,4866]}""" }
    val digests = tables.mapValues { (_, value) -> digest(value.toByteArray()) }
    val verifier get() = FingerprintFeedVerifier("0.1.1", keys.public.encoded, names.toSet())

    fun bundle(
        generatedAt: Instant = now,
        document: ByteArray = document(generatedAt),
        minimumVersion: String = "0.0.1",
    ): FingerprintBundle {
        val manifest = """{"schema":1,"name":"foxhole-tls-fingerprints","format":"tls-fingerprint-tables-json","generated_at":"$generatedAt","source":{"repo":"https://github.com/foxhole-team/foxhole-core","dataset":"fingerprints","license":"GPL-3.0-or-later","profile_count":4},"artifact":{"file":"fingerprints.json","size":${document.size},"sha256":"${digest(document)}"},"compatibility":{"min_app_version":"$minimumVersion"}}""".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(manifest); sign() }
        return FingerprintBundle(manifest, signature, document)
    }

    fun document(generatedAt: Instant = now): ByteArray {
        val profiles = names.joinToString(",") { name ->
            """{"schema":1,"name":"$name","describes":"Owned test profile","fingerprint_sha256":"${digests.getValue(name)}","fingerprint":${tables.getValue(name)}}"""
        }
        return """{"schema":1,"generated_at":"$generatedAt","profiles":[$profiles]}""".toByteArray()
    }

    companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
