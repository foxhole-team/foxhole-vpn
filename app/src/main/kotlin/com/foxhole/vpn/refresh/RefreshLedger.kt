package com.foxhole.vpn.refresh

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
internal enum class RefreshOutcome { NONE, CHECKING, UPDATED, UNCHANGED, AVAILABLE, FAILED, DEFERRED, STALE }

@Serializable
internal data class RefreshAttempt(
    val lastAttemptAtMillis: Long = 0,
    val lastSuccessAtMillis: Long = 0,
    val outcome: RefreshOutcome = RefreshOutcome.NONE,
)

@Serializable
internal data class AutomaticRefreshLedger(
    val subscriptions: Map<String, RefreshAttempt> = emptyMap(),
    val connectionData: RefreshAttempt = RefreshAttempt(),
)

internal fun subscriptionSourceKey(source: String): String =
    MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun refreshIsDue(stamp: RefreshAttempt?, nowMillis: Long, intervalMillis: Long): Boolean {
    require(intervalMillis > 0)
    if (stamp == null || stamp.lastAttemptAtMillis <= 0 || stamp.lastAttemptAtMillis > nowMillis) return true
    if (nowMillis - stamp.lastAttemptAtMillis < RETRY_DELAY_MILLIS && stamp.outcome in RetryOutcomes) return false
    return stamp.lastSuccessAtMillis <= 0 || stamp.lastSuccessAtMillis > nowMillis || nowMillis - stamp.lastSuccessAtMillis >= intervalMillis
}

internal fun RefreshAttempt.attempted(nowMillis: Long, result: RefreshOutcome): RefreshAttempt = copy(
    lastAttemptAtMillis = nowMillis,
    lastSuccessAtMillis = if (result == RefreshOutcome.UPDATED || result == RefreshOutcome.UNCHANGED || result == RefreshOutcome.AVAILABLE) nowMillis else lastSuccessAtMillis,
    outcome = result,
)

internal fun refreshIntervalMillis(hours: Int, fallback: Int): Long =
    (hours.takeIf { it in 1..168 } ?: fallback) * 60 * 60 * 1000L

private const val RETRY_DELAY_MILLIS = 10 * 60 * 1000L
private val RetryOutcomes =
    setOf(RefreshOutcome.CHECKING, RefreshOutcome.FAILED, RefreshOutcome.DEFERRED, RefreshOutcome.STALE)
