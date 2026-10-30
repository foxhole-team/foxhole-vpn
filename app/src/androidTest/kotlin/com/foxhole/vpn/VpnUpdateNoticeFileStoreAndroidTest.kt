package com.foxhole.vpn

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
class VpnUpdateNoticeFileStoreAndroidTest {
    private lateinit var directory: File

    @Before fun createOwnDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "update-notice-test-${UUID.randomUUID()}")
        check(directory.mkdir())
    }

    @After fun removeOwnDirectory() {
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    @Test fun acknowledgementSurvivesRestartWithoutChangingLicenseOrProfiles() = runBlocking {
        val license = File(directory, "license")
        VpnLicenseFileStore(license).writeAcceptedVersion(VPN_LICENSE_AGREEMENT_VERSION)
        val profile = File(directory, "profile").apply { writeBytes(byteArrayOf(9, 1, 3, 8)) }
        val licenseBefore = license.readBytes()
        val profileBefore = profile.readBytes()
        val record = File(directory, "notice")
        val notice = VpnUpdateNotice(VpnUpdateNoticeFileStore(record))
        notice.load()
        assertFalse(notice.state.value.acknowledged)
        assertFalse(record.exists())
        notice.acknowledge()
        assertTrue(notice.state.value.acknowledged)
        assertEquals(4L, record.length())
        val restored = VpnUpdateNotice(VpnUpdateNoticeFileStore(record))
        restored.load()
        assertTrue(restored.state.value.acknowledged)
        assertArrayEquals(licenseBefore, license.readBytes())
        assertArrayEquals(profileBefore, profile.readBytes())
    }

    @Test fun aNoticeRecordNeverGrantsLicenseAcceptance() = runBlocking {
        VpnUpdateNoticeFileStore(File(directory, "notice")).writeAcknowledgedRevision(VPN_UPDATE_NOTICE_REVISION)
        val license = VpnLicenseConsent(VpnLicenseFileStore(File(directory, "license")))
        license.load()
        assertFalse(license.state.value.accepted)
    }

    @Test fun malformedAcknowledgementRequiresTheNoticeAgain() = runBlocking {
        val record = File(directory, "notice").apply { writeBytes(byteArrayOf(0, 0, 0, 1, 0)) }
        val notice = VpnUpdateNotice(VpnUpdateNoticeFileStore(record))
        notice.load()
        assertTrue(notice.state.value.loaded)
        assertFalse(notice.state.value.acknowledged)
        assertTrue(notice.state.value.storageError)
        notice.acknowledge()
        assertTrue(notice.state.value.acknowledged)
    }
}
