package com.foxhole.vpn

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLicenseFileStoreAndroidTest {
    private lateinit var directory: File
    private lateinit var record: File

    @Before fun createOwnDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "license-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        record = File(directory, "agreement")
    }

    @After fun removeOwnDirectory() {
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    @Test fun explicitConsentPersistsWithoutTouchingOtherFiles() = runBlocking {
        val other = File(directory, "saved-profile").apply { writeBytes(byteArrayOf(8, 3, 6, 1)) }
        val before = other.readBytes()
        val consent = VpnLicenseConsent(VpnLicenseFileStore(record))
        consent.load()
        assertFalse(consent.state.value.accepted)
        assertFalse(record.exists())
        consent.accept(confirmed = false)
        assertFalse(record.exists())
        consent.accept(confirmed = true)
        assertTrue(consent.state.value.accepted)
        assertEquals(4L, record.length())
        val restored = VpnLicenseConsent(VpnLicenseFileStore(record))
        restored.load()
        assertTrue(restored.state.value.accepted)
        assertArrayEquals(before, other.readBytes())
    }

    @Test fun corruptedOrDifferentVersionRecordsRequireAgreementAgain() = runBlocking {
        for (bytes in listOf(byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 2), byteArrayOf(0, 0, 0, 1, 0))) {
            record.writeBytes(bytes)
            val consent = VpnLicenseConsent(VpnLicenseFileStore(record))
            consent.load()
            assertTrue(consent.state.value.loaded)
            assertFalse(consent.state.value.accepted)
        }
    }

    @Test fun interruptedFirstWriteCannotCreateAcceptance() = runBlocking {
        val atomic = AtomicFile(record)
        val output = atomic.startWrite()
        try {
            output.write(byteArrayOf(0, 0, 0))
            output.flush()
            val restored = VpnLicenseConsent(VpnLicenseFileStore(record))
            restored.load()
            assertFalse(restored.state.value.accepted)
        } finally {
            atomic.failWrite(output)
        }
    }

    @Test fun atomicRollbackKeepsThePreviouslyAcceptedVersion() = runBlocking {
        VpnLicenseFileStore(record).writeAcceptedVersion(VPN_LICENSE_AGREEMENT_VERSION)
        val atomic = AtomicFile(record)
        val output = atomic.startWrite()
        output.write(byteArrayOf(0, 0, 0))
        atomic.failWrite(output)
        val restored = VpnLicenseConsent(VpnLicenseFileStore(record))
        restored.load()
        assertTrue(restored.state.value.accepted)
    }
}
