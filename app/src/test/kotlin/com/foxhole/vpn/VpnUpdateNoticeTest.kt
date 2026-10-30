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

internal class VpnUpdateNoticeTest {
    private class Store(var revision: Int = 0) : VpnUpdateNoticeStore {
        var writes = 0
        var failRead = false
        var failWrite = false
        var discardWrite = false
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun readAcknowledgedRevision(): Int {
            if (failRead) throw IOException("notice_read")
            return revision
        }

        override suspend fun writeAcknowledgedRevision(revision: Int) {
            beforeWrite()
            if (failWrite) throw IOException("notice_write")
            writes++
            if (!discardWrite) this.revision = revision
        }
    }

    @Test fun freshAndOlderRecordsRequireAcknowledgement() = runBlocking {
        for (revision in listOf(-1, 0)) {
            val store = Store(revision)
            val notice = VpnUpdateNotice(store)
            assertFalse(notice.state.value.loaded)
            assertFalse(notice.state.value.acknowledged)
            notice.load()
            assertTrue(notice.state.value.loaded)
            assertFalse(notice.state.value.acknowledged)
            assertEquals(0, store.writes)
        }
    }

    @Test fun currentAndNewerAcknowledgementsAreNotDowngraded() = runBlocking {
        for (revision in listOf(VPN_UPDATE_NOTICE_REVISION, VPN_UPDATE_NOTICE_REVISION + 1)) {
            val store = Store(revision)
            val notice = VpnUpdateNotice(store)
            notice.load()
            assertTrue(notice.state.value.acknowledged)
            notice.acknowledge()
            assertEquals(revision, store.revision)
            assertEquals(0, store.writes)
        }
    }

    @Test fun acknowledgementCannotWriteBeforeLoading() = runBlocking {
        val store = Store()
        val notice = VpnUpdateNotice(store)
        notice.acknowledge()
        assertFalse(notice.state.value.acknowledged)
        assertEquals(0, store.writes)
    }

    @Test fun dismissalCommitsBeforeRemovingTheNoticeAndSurvivesRestart() = runBlocking {
        val store = Store()
        val notice = VpnUpdateNotice(store)
        notice.load()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = { entered.complete(Unit); release.await() }
        val job = launch { notice.acknowledge() }
        entered.await()
        assertTrue(notice.state.value.saving)
        assertFalse(notice.state.value.acknowledged)
        assertFalse(VpnUpdateNotice(store).also { it.load() }.state.value.acknowledged)
        release.complete(Unit)
        job.join()
        assertTrue(notice.state.value.acknowledged)
        assertFalse(notice.state.value.saving)
        assertEquals(VPN_UPDATE_NOTICE_REVISION, store.revision)
        assertTrue(VpnUpdateNotice(store).also { it.load() }.state.value.acknowledged)
        notice.acknowledge()
        assertEquals(1, store.writes)
    }

    @Test fun failedOrMismatchedPersistenceStaysVisibleAndCanBeRetried() = runBlocking {
        for (discardWrite in listOf(false, true)) {
            val store = Store().apply {
                failWrite = !discardWrite
                this.discardWrite = discardWrite
            }
            val notice = VpnUpdateNotice(store)
            notice.load()
            notice.acknowledge()
            assertFalse(notice.state.value.acknowledged)
            assertFalse(notice.state.value.saving)
            assertTrue(notice.state.value.storageError)
            assertEquals(1, notice.state.value.retryGeneration)
            store.failWrite = false
            store.discardWrite = false
            notice.acknowledge()
            assertTrue(notice.state.value.acknowledged)
            assertFalse(notice.state.value.storageError)
        }
    }

    @Test fun unreadableRecordRequiresTheNoticeAndRetry() = runBlocking {
        val store = Store(VPN_UPDATE_NOTICE_REVISION).apply { failRead = true }
        val notice = VpnUpdateNotice(store)
        notice.load()
        assertTrue(notice.state.value.loaded)
        assertTrue(notice.state.value.storageError)
        assertFalse(notice.state.value.acknowledged)
        assertEquals(0, store.writes)
    }

    @Test fun cancellationBeforeCommitRequiresTheNoticeAfterRestart() = runBlocking {
        val store = Store()
        val notice = VpnUpdateNotice(store)
        notice.load()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = { entered.complete(Unit); release.await() }
        val job = launch { notice.acknowledge() }
        entered.await()
        job.cancelAndJoin()
        assertFalse(notice.state.value.acknowledged)
        assertFalse(notice.state.value.saving)
        assertEquals(0, store.writes)
        assertFalse(VpnUpdateNotice(store).also { it.load() }.state.value.acknowledged)
    }

    @Test fun concurrentDismissalsCommitOnlyOnce() = runBlocking {
        val store = Store()
        val notice = VpnUpdateNotice(store)
        notice.load()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeWrite = { entered.complete(Unit); release.await() }
        val first = launch { notice.acknowledge() }
        entered.await()
        val second = launch { notice.acknowledge() }
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, store.writes)
        assertTrue(notice.state.value.acknowledged)
    }
}
