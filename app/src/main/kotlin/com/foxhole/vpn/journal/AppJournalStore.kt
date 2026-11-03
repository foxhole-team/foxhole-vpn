package com.foxhole.vpn.journal

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal enum class AppJournalRetention(val ageMillis: Long?) {
    DAY(86_400_000L), WEEK(7 * 86_400_000L), MONTH(30 * 86_400_000L), FOREVER(null),
}

// The journal accepts no free-form payload, exception message, address, or profile identifier.
internal enum class AppJournalEvent {
    APP_STARTED, VPN_IDLE, VPN_CONNECTING, VPN_VALIDATING, VPN_CONNECTED, VPN_STOPPING, VPN_ERROR,
    VPN_CONNECT_REQUESTED, VPN_DISCONNECT_REQUESTED,
    SCENARIO_APPLY_STARTED, SCENARIO_APPLIED, SCENARIO_APPLY_FAILED,
    PROFILE_IMPORT_FINISHED, APP_UPDATE_CHECK_FINISHED, APP_UPDATE_INSTALL_REQUESTED,
    ROUTER_OPERATION_FINISHED, JOURNAL_EXPORTED,
}

internal enum class AppJournalResult { NONE, SUCCESS, CANCELLED, NETWORK, VALIDATION, STORAGE, PERMISSION, POLICY, NATIVE, UNKNOWN }
internal data class AppJournalEntry(val timeMillis: Long, val event: AppJournalEvent, val result: AppJournalResult = AppJournalResult.NONE)
internal data class AppJournalPreview(val retention: AppJournalRetention, val entries: List<AppJournalEntry>)

internal object AppJournalCodec {
    fun encode(entry: AppJournalEntry): String = "${entry.timeMillis}\t${entry.event.name}\t${entry.result.name}\n"

    fun decode(line: String): AppJournalEntry? {
        if (line.length > 192) return null
        val fields = line.split('\t')
        if (fields.size != 3) return null
        val time = fields[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val event = AppJournalEvent.entries.firstOrNull { it.name == fields[1] } ?: return null
        val result = AppJournalResult.entries.firstOrNull { it.name == fields[2] } ?: return null
        return AppJournalEntry(time, event, result)
    }

    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    fun display(entry: AppJournalEntry): String = "${timestamp.format(Instant.ofEpochMilli(entry.timeMillis))}\t${entry.event.name}\t${if (entry.result == AppJournalResult.NONE) "-" else entry.result.name}"
}

// Calls are serialized by AppJournal. Segments bound memory, never the retained history.
internal class AppJournalStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis,
    private val segmentByteLimit: Int = 256 * 1024) {
    init { require(segmentByteLimit in 256..256 * 1024) }
    private val retentionFile get() = File(directory, "retention-v1")
    private val segmentPattern = Regex("journal-([0-9]{8})-([0-9]{4})\\.log")
    private val dayFormat = DateTimeFormatter.BASIC_ISO_DATE

    fun retention(): AppJournalRetention {
        if (!retentionFile.exists()) return AppJournalRetention.DAY
        if (retentionFile.length() > 32) throw IOException("journal_retention_invalid")
        return AppJournalRetention.entries.firstOrNull { it.name == retentionFile.readText(Charsets.UTF_8).trim() }
            ?: throw IOException("journal_retention_invalid")
    }

    fun setRetention(value: AppJournalRetention, checkCancelled: () -> Unit = {}) {
        ensureDirectory()
        replaceAtomically(retentionFile, (value.name + "\n").toByteArray(Charsets.UTF_8))
        prune(value, checkCancelled = checkCancelled)
    }

    fun append(event: AppJournalEvent, result: AppJournalResult = AppJournalResult.NONE, checkCancelled: () -> Unit = {}) {
        ensureDirectory()
        val now = clock().coerceAtLeast(0)
        prune(retention(), now, checkCancelled)
        val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().format(dayFormat)
        val files = segments().filter { it.name.startsWith("journal-$day-") }.sortedBy { it.name }
        val line = AppJournalCodec.encode(AppJournalEntry(now, event, result)).toByteArray(Charsets.UTF_8)
        val last = files.lastOrNull()
        val target = if (last != null && last.length() + line.size <= segmentByteLimit) last else {
            val next = (last?.name?.let { segmentPattern.matchEntire(it)?.groupValues?.get(2)?.toInt() } ?: -1) + 1
            if (next > 9999) throw IOException("journal_daily_segment_limit")
            File(directory, "journal-$day-${next.toString().padStart(4, '0')}.log")
        }
        val previous = if (target.exists()) readBounded(target) else ByteArray(0)
        replaceAtomically(target, previous + line)
    }

    fun preview(limit: Int = 500, checkCancelled: () -> Unit = {}): AppJournalPreview {
        require(limit in 1..1000)
        val policy = retention()
        val now = clock().coerceAtLeast(0)
        prune(policy, now, checkCancelled)
        val result = ArrayList<AppJournalEntry>(limit)
        for (file in segments().sortedByDescending { it.name }) {
            checkCancelled()
            for (entry in entries(file).asReversed()) {
                if (retained(entry, policy, now)) result += entry
                if (result.size == limit) return AppJournalPreview(policy, result.asReversed())
            }
        }
        return AppJournalPreview(policy, result.asReversed())
    }

    fun export(output: OutputStream, checkCancelled: () -> Unit = {}) {
        val policy = retention()
        val now = clock().coerceAtLeast(0)
        prune(policy, now, checkCancelled)
        val writer = output.writer(Charsets.UTF_8)
        writer.write("FoxHole VPN application journal\nTime zone: UTC\nFormat: 1\nRetention: ${policy.name}\nTIME UTC\tEVENT\tRESULT\n")
        for (file in segments().sortedBy { it.name }) {
            checkCancelled()
            for (entry in entries(file)) if (retained(entry, policy, now)) {
                writer.write(AppJournalCodec.display(entry)); writer.write("\n")
            }
        }
        writer.flush()
    }

    fun prune(policy: AppJournalRetention = retention(), now: Long = clock().coerceAtLeast(0), checkCancelled: () -> Unit = {}) {
        val age = policy.ageMillis ?: return
        val cutoff = now - age
        for (file in segments()) {
            checkCancelled()
            val day = segmentPattern.matchEntire(file.name)?.groupValues?.get(1)?.let {
                runCatching { LocalDate.parse(it, dayFormat) }.getOrNull()
            } ?: continue
            if (day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() <= cutoff) {
                if (!file.delete() && file.exists()) throw IOException("journal_prune_failed")
            } else if (day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() < cutoff) {
                val original = readBounded(file)
                val remaining = original.toString(Charsets.UTF_8).lineSequence().mapNotNull(AppJournalCodec::decode)
                    .filter { retained(it, policy, now) }.joinToString("") { AppJournalCodec.encode(it) }.toByteArray(Charsets.UTF_8)
                if (remaining.isEmpty()) {
                    if (!file.delete() && file.exists()) throw IOException("journal_prune_failed")
                } else if (!original.contentEquals(remaining)) replaceAtomically(file, remaining)
            }
        }
    }

    private fun retained(entry: AppJournalEntry, policy: AppJournalRetention, now: Long): Boolean =
        policy.ageMillis?.let { entry.timeMillis >= now - it } ?: true

    private fun segments(): List<File> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("journal_directory_unreadable")
        return files.filter { it.isFile && segmentPattern.matches(it.name) }
    }

    private fun entries(file: File): List<AppJournalEntry> = readBounded(file).toString(Charsets.UTF_8)
        .lineSequence().mapNotNull(AppJournalCodec::decode).toList()

    private fun readBounded(file: File): ByteArray {
        if (file.length() > segmentByteLimit) throw IOException("journal_segment_invalid")
        return file.inputStream().use { input ->
            val buffer = ByteArray(segmentByteLimit + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > segmentByteLimit) throw IOException("journal_segment_invalid")
            buffer.copyOf(count)
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("journal_directory_unavailable")
    }

    private fun replaceAtomically(target: File, bytes: ByteArray) {
        val pending = File(directory, target.name + ".new")
        try {
            pending.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
}
