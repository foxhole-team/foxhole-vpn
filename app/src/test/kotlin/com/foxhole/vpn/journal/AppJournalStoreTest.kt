package com.foxhole.vpn.journal

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppJournalStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
    private fun store(limit: Int = 256 * 1024) = AppJournalStore(temporary.root, { now }, limit)

    @Test fun newJournalDefaultsTo24HoursAndPersistsAnExplicitForeverChoice() {
        assertEquals(AppJournalRetention.DAY, store().retention())
        store().setRetention(AppJournalRetention.FOREVER)
        assertEquals(AppJournalRetention.FOREVER, store().retention())
    }

    @Test fun readAndAppendExpireOldEntriesAtTheExactDayBoundary() {
        val journal = store()
        journal.append(AppJournalEvent.APP_STARTED)
        now += 86_400_000L
        assertEquals(1, journal.preview().entries.size)
        now += 1
        assertTrue(journal.preview().entries.isEmpty())
        journal.append(AppJournalEvent.VPN_CONNECTED)
        assertEquals(listOf(AppJournalEvent.VPN_CONNECTED), journal.preview().entries.map { it.event })
        assertEquals(1, temporary.root.listFiles()!!.count { it.extension == "log" })
    }

    @Test fun changingToShorterRetentionPrunesOldSegmentsWithoutTouchingOtherFiles() {
        val journal = store()
        journal.setRetention(AppJournalRetention.FOREVER)
        journal.append(AppJournalEvent.APP_STARTED)
        now += 8 * 86_400_000L
        journal.append(AppJournalEvent.VPN_CONNECTED)
        val unrelated = temporary.newFile("owner-data").apply { writeText("preserve") }
        journal.setRetention(AppJournalRetention.WEEK)
        assertEquals(listOf(AppJournalEvent.VPN_CONNECTED), journal.preview().entries.map { it.event })
        assertEquals("preserve", unrelated.readText())
    }

    @Test fun foreverRotatesSmallSegmentsWithoutDeletingHistoryAndExportIncludesBeyondPreview() {
        val journal = store(512)
        journal.setRetention(AppJournalRetention.FOREVER)
        repeat(620) { index ->
            journal.append(if (index % 2 == 0) AppJournalEvent.VPN_CONNECTING else AppJournalEvent.VPN_CONNECTED)
            now += 1000
        }
        now += 400 * 86_400_000L
        assertEquals(500, journal.preview().entries.size)
        assertTrue(temporary.root.listFiles()!!.count { it.extension == "log" } > 1)
        val output = ByteArrayOutputStream()
        journal.export(output)
        val export = output.toString("UTF-8")
        assertEquals(620, export.lineSequence().count { it.contains("\tVPN_CONNECT") })
        assertTrue(export.contains("Retention: FOREVER"))
        assertTrue(export.contains("Time zone: UTC"))
    }

    @Test fun forgedFreeFormPayloadsAreRejectedAndNeverReachPreviewOrExport() {
        val journal = store()
        journal.append(AppJournalEvent.VPN_ERROR, AppJournalResult.NETWORK)
        val file = temporary.root.listFiles()!!.single { it.extension == "log" }
        val secret = "https://user:token@example.invalid/192.0.2.1?subscription=secret"
        file.appendText("$now\tVPN_ERROR\t$secret\n$now\tVPN_ERROR\tNETWORK\t$secret\n$secret\n")
        assertEquals(1, journal.preview().entries.size)
        val output = ByteArrayOutputStream(); journal.export(output)
        assertFalse(output.toString("UTF-8").contains(secret))
        assertFalse(output.toString("UTF-8").contains("192.0.2.1"))
        assertTrue(AppJournalCodec.decode("$now\tVPN_ERROR\tNETWORK\n") == null)
    }

    @Test fun interruptedExportDoesNotDeleteOrChangeRetainedHistory() {
        val journal = store(512)
        journal.setRetention(AppJournalRetention.FOREVER)
        repeat(30) { journal.append(AppJournalEvent.APP_STARTED); now++ }
        val before = temporary.root.listFiles()!!.filter { it.extension == "log" }.associate { it.name to Files.readAllBytes(it.toPath()).toList() }
        assertTrue(runCatching { journal.export(ByteArrayOutputStream()) { throw InterruptedException("cancelled") } }.isFailure)
        val after = temporary.root.listFiles()!!.filter { it.extension == "log" }.associate { it.name to Files.readAllBytes(it.toPath()).toList() }
        assertEquals(before, after)
        val output = ByteArrayOutputStream(); journal.export(output)
        assertEquals(30, output.toString("UTF-8").lineSequence().count { it.contains("\tAPP_STARTED\t") })
    }
}
