package com.foxhole.vpn.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicBoolean
import com.foxhole.core.runtime.FoxholeNativeEngine

/** Owns one retained token, never a native engine or routing permission. */
internal class DnsContinuationLease(private val releaseToken: (Long) -> Unit) : AutoCloseable {
    private val lock = Any()
    private var captured: Long? = null
    private var retained = 0L
    private var ready = false
    private var started = false
    private var inFlight = false
    private var closed = false

    internal fun captured(token: Long) {
        require(token > 0) { "DNS continuation capture failed" }
        val release = synchronized(lock) {
            val previous = captured
            if (previous != null) {
                check(previous == token) { "DNS continuation captured twice" }
                0L
            } else {
                captured = token
                if (closed) token else { retained = token; 0L }
            }
        }
        if (release > 0) releaseToken(release)
    }

    internal fun oldStoppedCleanly() = synchronized(lock) {
        check(!closed && retained > 0 && !started) { "DNS continuation stop fence missing" }
        ready = true
    }

    /** Called inside the existing native executor immediately before start. */
    internal fun beginStart(): Start = synchronized(lock) {
        check(!closed && ready && retained > 0 && !started) { "DNS continuation unavailable" }
        started = true
        inFlight = true
        Start(this, retained)
    }

    override fun close() {
        val release = synchronized(lock) {
            closed = true
            if (inFlight) 0L else retained.also { retained = 0 }
        }
        if (release > 0) releaseToken(release)
    }

    private fun finished() {
        val release = synchronized(lock) {
            check(inFlight) { "DNS continuation start already settled" }
            inFlight = false
            closed = true
            retained.also { retained = 0 }
        }
        if (release > 0) releaseToken(release)
    }

    internal class Start internal constructor(private val lease: DnsContinuationLease, internal val token: Long) {
        private val done = AtomicBoolean(false)

        /** After normal completion, or after existing late-handle cleanup. */
        internal fun finished() { if (done.compareAndSet(false, true)) lease.finished() }
    }
}

internal fun managedWireGuardPacketDns(protocols: Set<String>, engineJson: String): Boolean {
    if (protocols != setOf("wireguard")) return false
    val dns = Json.parseToJsonElement(engineJson).jsonObject.getValue("dns").jsonObject
    return (dns["packet_resolvers"] as? JsonArray)?.isNotEmpty() == true
}

internal fun dnsContinuationStartUnsettled(result: Long): Boolean =
    result == FoxholeNativeEngine.DNS_CONTINUITY_START_QUARANTINED ||
        result == FoxholeNativeEngine.DNS_CONTINUITY_START_FAILED

internal fun requireDnsContinuationSettled(settled: Boolean) {
    if (!settled) throw EngineException(EngineFailure.START, processPoisoned = true)
}

internal fun dnsContinuationStopClean(result: Int?): Boolean =
    result == FoxholeNativeEngine.STOPPED || result == FoxholeNativeEngine.ALREADY_STOPPED
