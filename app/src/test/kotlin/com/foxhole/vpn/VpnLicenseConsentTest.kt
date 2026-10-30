package com.foxhole.vpn

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class VpnLicenseConsentTest {
    private class Store(var version: Int = 0) : VpnLicenseStore {
        var writes = 0
        var failRead = false
        var failWrite = false
        var discardWrite = false
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun readAcceptedVersion(): Int {
            if (failRead) throw IOException("license_read")
            return version
        }

        override suspend fun writeAcceptedVersion(version: Int) {
            beforeWrite()
            if (failWrite) throw IOException("license_write")
            writes++
            if (!discardWrite) this.version = version
        }
    }

    @Test fun freshAndUnknownVersionsRequireAnExplicitAgreement() = runBlocking {
        for (version in listOf(0, -1, VPN_LICENSE_AGREEMENT_VERSION + 1)) {
            val store = Store(version)
            val consent = VpnLicenseConsent(store)
            assertFalse(consent.state.value.loaded)
            assertFalse(consent.state.value.accepted)
            consent.load()
            assertTrue(consent.state.value.loaded)
            assertFalse(consent.state.value.accepted)
            assertEquals(0, store.writes)
        }
    }

    @Test fun uncheckedOrUnloadedContinueCannotWriteAcceptance() = runBlocking {
        val store = Store()
        val consent = VpnLicenseConsent(store)
        consent.accept(confirmed = true)
        consent.load()
        consent.accept(confirmed = false)
        assertFalse(consent.state.value.accepted)
        assertEquals(0, store.writes)
        assertEquals(0, store.version)
    }

    @Test fun explicitAcceptanceIsDurableBeforeTheGateOpensAndSurvivesRelaunch() = runBlocking {
        val store = Store()
        val consent = VpnLicenseConsent(store)
        consent.load()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = { entered.complete(Unit); release.await() }
        val job = launch { consent.accept(confirmed = true) }
        entered.await()
        assertTrue(consent.state.value.saving)
        assertFalse(consent.state.value.accepted)
        assertFalse(VpnLicenseConsent(store).also { it.load() }.state.value.accepted)
        release.complete(Unit)
        job.join()
        assertTrue(consent.state.value.accepted)
        assertFalse(consent.state.value.saving)
        assertEquals(VPN_LICENSE_AGREEMENT_VERSION, store.version)
        assertTrue(VpnLicenseConsent(store).also { it.load() }.state.value.accepted)
        consent.accept(confirmed = true)
        assertEquals(1, store.writes)
    }

    @Test fun failedOrMismatchedPersistenceKeepsTheGateClosedAndCanBeRetried() = runBlocking {
        for (discardWrite in listOf(false, true)) {
            val store = Store().apply {
                failWrite = !discardWrite
                this.discardWrite = discardWrite
            }
            val consent = VpnLicenseConsent(store)
            consent.load()
            consent.accept(confirmed = true)
            assertFalse(consent.state.value.accepted)
            assertFalse(consent.state.value.saving)
            assertTrue(consent.state.value.storageError)
            store.failWrite = false
            store.discardWrite = false
            consent.accept(confirmed = true)
            assertTrue(consent.state.value.accepted)
            assertFalse(consent.state.value.storageError)
        }
    }

    @Test fun unreadableConsentNeverFallsThroughToTheApp() = runBlocking {
        val store = Store(VPN_LICENSE_AGREEMENT_VERSION).apply { failRead = true }
        val consent = VpnLicenseConsent(store)
        consent.load()
        assertTrue(consent.state.value.loaded)
        assertTrue(consent.state.value.storageError)
        assertFalse(consent.state.value.accepted)
        assertEquals(0, store.writes)
    }

    @Test fun processInterruptionBeforeCommitRequiresAgreementAgain() = runBlocking {
        val store = Store()
        val consent = VpnLicenseConsent(store)
        consent.load()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = { entered.complete(Unit); release.await() }
        val job = launch { consent.accept(confirmed = true) }
        entered.await()
        job.cancelAndJoin()
        assertFalse(consent.state.value.accepted)
        assertFalse(consent.state.value.saving)
        assertEquals(0, store.writes)
        assertFalse(VpnLicenseConsent(store).also { it.load() }.state.value.accepted)
    }
}
