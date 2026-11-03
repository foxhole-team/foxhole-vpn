package com.foxhole.vpn.journal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnPhase
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AppJournalState(val loaded: Boolean = false, val retention: AppJournalRetention = AppJournalRetention.DAY,
    val entries: List<AppJournalEntry> = emptyList(), val storageError: Boolean = false)

internal class AppJournal private constructor(private val store: AppJournalStore) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AppJournalState())
    val state: StateFlow<AppJournalState> = mutable

    suspend fun load(): Boolean = mutate { }
    suspend fun record(event: AppJournalEvent, result: AppJournalResult = AppJournalResult.NONE): Boolean =
        mutate { checkCancelled -> store.append(event, result, checkCancelled) }
    suspend fun setRetention(value: AppJournalRetention): Boolean =
        mutate { checkCancelled -> store.setRetention(value, checkCancelled) }

    // The lock protects the complete streamed snapshot against append and retention deletion.
    suspend fun exportTo(output: OutputStream): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val context = currentCoroutineContext()
                store.export(output) { context.ensureActive() }
                true
            } catch (_: IOException) { fail(); false }
            catch (_: SecurityException) { fail(); false }
        }
    }

    suspend fun saveTo(resolver: ContentResolver, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val saved = resolver.openOutputStream(destination, "wt")?.use { exportTo(it) } ?: false
            if (saved) record(AppJournalEvent.JOURNAL_EXPORTED, AppJournalResult.SUCCESS) else fail()
            saved
        } catch (_: IOException) { fail(); false }
        catch (_: SecurityException) { fail(); false }
    }

    private suspend fun mutate(action: (checkCancelled: () -> Unit) -> Unit): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val context = currentCoroutineContext()
                val checkCancelled = { context.ensureActive() }
                action(checkCancelled)
                val preview = store.preview(checkCancelled = checkCancelled)
                mutable.value = AppJournalState(true, preview.retention, preview.entries)
                true
            } catch (_: IOException) { fail(); false }
            catch (_: SecurityException) { fail(); false }
        }
    }

    private fun fail() { mutable.value = mutable.value.copy(loaded = true, storageError = true) }

    companion object {
        @Volatile private var singleton: AppJournal? = null
        fun shared(context: Context): AppJournal = singleton ?: synchronized(this) {
            singleton ?: AppJournal(AppJournalStore(File(context.applicationContext.noBackupFilesDir, "app-journal")))
                .also { singleton = it }
        }

        fun exportFileName(now: Instant = Instant.now()): String = "FoxHole-VPN-journal-${DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC).format(now)}.log"

        fun start(context: Context, scope: CoroutineScope, controller: VpnController) {
            val journal = shared(context)
            AppJournalWork.schedule(context)
            scope.launch {
                journal.record(AppJournalEvent.APP_STARTED)
                controller.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                    val event = when (phase) {
                        VpnPhase.IDLE -> AppJournalEvent.VPN_IDLE
                        VpnPhase.CONNECTING -> AppJournalEvent.VPN_CONNECTING
                        VpnPhase.VALIDATING -> AppJournalEvent.VPN_VALIDATING
                        VpnPhase.CONNECTED -> AppJournalEvent.VPN_CONNECTED
                        VpnPhase.STOPPING -> AppJournalEvent.VPN_STOPPING
                        VpnPhase.ERROR -> AppJournalEvent.VPN_ERROR
                    }
                    journal.record(event, if (phase == VpnPhase.ERROR) AppJournalResult.UNKNOWN else AppJournalResult.NONE)
                }
            }
        }
    }
}
