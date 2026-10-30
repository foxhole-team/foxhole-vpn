package com.foxhole.vpn.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class FingerprintFeedVerifierTest {
    private val fixture = FingerprintFeedFixture()

    @Test fun productionKeyAuthenticatesThePublishedOwnerManifest() {
        val loader = requireNotNull(javaClass.classLoader)
        val raw = requireNotNull(loader.getResourceAsStream("fingerprints/manifest-20261007.json")).use { it.readBytes() }
        val signature = requireNotNull(loader.getResourceAsStream("fingerprints/manifest-20261007.json.sig")).use { it.readBytes() }
        val manifest = FingerprintFeedVerifier("0.1.1").manifest(raw, signature, Instant.parse("2026-10-08T00:00:00Z"))
        assertEquals(9, manifest.profileCount)
        assertEquals(99345L, manifest.size)
        assertEquals("73c2d86a587596e4aa4b9a568247ba9d7174f747950a79e0b0a55854d5d36013", manifest.sha256)
    }

    @Test fun authenticBundleBindsEveryProfileAndTimestamp() {
        val verified = fixture.verifier.verify(fixture.bundle(), fixture.now)
        assertEquals(fixture.digests, verified.profileDigests)
        assertEquals(fixture.now, verified.manifest.generatedAt)
    }

    @Test fun changedManifestBytesFailBeforeParsing() {
        val bundle = fixture.bundle()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.verifier.manifest(bundle.manifest + 32, bundle.signature, fixture.now)
        }
    }

    @Test fun staleFutureAndIncompatibleFreshCandidatesAreRefused() {
        for (date in listOf(fixture.now.minus(46, ChronoUnit.DAYS), fixture.now.plus(2, ChronoUnit.DAYS))) {
            assertThrows(IllegalArgumentException::class.java) { fixture.verifier.verify(fixture.bundle(date), fixture.now) }
        }
        assertThrows(IllegalArgumentException::class.java) { fixture.verifier.verify(fixture.bundle(minimumVersion = "0.2.0"), fixture.now) }
    }

    @Test fun previouslyAcceptedCacheDoesNotExpireOffline() {
        val old = fixture.bundle(fixture.now.minus(90, ChronoUnit.DAYS))
        assertEquals(4, fixture.verifier.verify(old, fixture.now, fresh = false).profileDigests.size)
    }

    @Test fun artifactDigestAndOwnProfileDigestAreIndependentAdmissionChecks() {
        val bundle = fixture.bundle()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.verifier.verify(bundle.copy(document = bundle.document + 32), fixture.now)
        }
        val changed = fixture.document().toString(Charsets.UTF_8).replace("4865", "4867").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { fixture.verifier.verify(fixture.bundle(document = changed), fixture.now) }
    }

    @Test fun unknownDuplicateAndDifferentTimestampProfilesAreRefused() {
        val raw = fixture.document().toString(Charsets.UTF_8)
        for (changed in listOf(raw.replace("chrome_151", "unknown_151"), raw.replace("chrome_133", "chrome_151"),
            raw.replace(fixture.now.toString(), fixture.now.minusSeconds(1).toString()))) {
            assertThrows(IllegalArgumentException::class.java) {
                fixture.verifier.verify(fixture.bundle(document = changed.toByteArray()), fixture.now)
            }
        }
    }

    @Test fun rollbackAndEqualTimeEquivocationAreRefused() {
        val installed = fixture.verifier.verify(fixture.bundle(), fixture.now).manifest
        val older = fixture.verifier.verify(fixture.bundle(fixture.now.minusSeconds(1)), fixture.now).manifest
        assertThrows(IllegalArgumentException::class.java) { older.requireNotOlderThan(installed) }
        assertThrows(IllegalArgumentException::class.java) { installed.copy(sha256 = "0".repeat(64)).requireNotOlderThan(installed) }
    }
}
