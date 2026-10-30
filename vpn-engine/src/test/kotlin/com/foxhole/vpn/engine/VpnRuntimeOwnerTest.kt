package com.foxhole.vpn.engine

import com.foxhole.vpn.profile.VpnProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@OptIn(ExperimentalCoroutinesApi::class)
class VpnRuntimeOwnerTest {
    @Test
    fun `native startup alone never publishes connected`() = runTest {
        val session = FakeSession()
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver)
        runCurrent()

        assertEquals(EnginePhase.VALIDATING, owner.state.value.phase)
        session.validated.complete(location)
        runCurrent()
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
        assertEquals(location, owner.state.value.location)
    }

    @Test
    fun `stop during native startup disposes late owner without publishing connected`() = runTest {
        val phases = mutableListOf<EnginePhase>()
        val driver = FakeDriver()
        val session = FakeSession().apply { validated.complete(location) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        backgroundScope.launch { owner.state.collect { phases += it.phase } }
        owner.connect(profile("first"), driver)
        runCurrent()
        owner.stop()
        runCurrent()
        assertEquals(1, driver.aborts)
        driver.opened.complete(session)
        runCurrent()

        assertEquals(1, session.stops)
        assertEquals(EnginePhase.IDLE, owner.state.value.phase)
        assertFalse(EnginePhase.CONNECTED in phases)
        assertEquals(null, owner.state.value.location)
    }

    @Test
    fun `replacement cannot start before previous native cleanup settles`() = runTest {
        val old = FakeSession().apply { validated.complete(location); stopGate = CompletableDeferred() }
        val first = FakeDriver().apply { opened.complete(old) }
        val next = FakeDriver()
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), first)
        runCurrent()
        owner.connect(profile("next"), next)
        runCurrent()

        assertEquals(EnginePhase.STOPPING, owner.state.value.phase)
        assertEquals(0, next.starts)
        old.stopGate.complete(EngineStopResult(true))
        runCurrent()
        assertEquals(1, next.starts)
        assertEquals(EnginePhase.CONNECTING, owner.state.value.phase)
        assertEquals(null, owner.state.value.location)
    }

    @Test
    fun `validation failure tears down rather than claiming connectivity`() = runTest {
        val session = FakeSession().apply { validated.completeExceptionally(EngineException(EngineFailure.VALIDATION)) }
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver)
        runCurrent()

        assertEquals(1, session.stops)
        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertEquals(EngineFailure.VALIDATION, owner.state.value.failure)
    }

    @Test
    fun `nested validation deadline publishes failure and releases the unvalidated tunnel`() = runTest {
        val session = FakeSession().apply { validationDeadline = true }
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver)
        runCurrent()
        assertEquals(EnginePhase.VALIDATING, owner.state.value.phase)

        advanceTimeBy(2)
        runCurrent()

        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertEquals(EngineFailure.VALIDATION, owner.state.value.failure)
        assertEquals(1, session.stops)
        assertFalse(owner.state.value.hasOwnedTunnel)
        assertEquals("TIMEOUT", owner.state.value.validationFailureCode)
    }

    @Test
    fun `nested revalidation deadline retains validated tunnel with honest error`() = runTest {
        val session = FakeSession().apply { validated.complete(location) }
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver)
        runCurrent()
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)

        session.validationDeadline = true
        owner.networkChanged()
        runCurrent()
        advanceTimeBy(2)
        runCurrent()

        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertEquals(EngineFailure.VALIDATION, owner.state.value.failure)
        assertTrue(owner.state.value.hasOwnedTunnel)
        assertEquals(null, owner.state.value.location)
        assertEquals(0, session.stops)
        assertEquals("TIMEOUT", owner.state.value.validationFailureCode)
    }

    @Test
    fun `explicit stop cancellation does not become a stale validation failure`() = runTest {
        val session = FakeSession()
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver)
        runCurrent()
        assertEquals(EnginePhase.VALIDATING, owner.state.value.phase)

        owner.stop()
        runCurrent()

        assertEquals(EnginePhase.IDLE, owner.state.value.phase)
        assertEquals(null, owner.state.value.failure)
        assertEquals(1, session.stops)
        assertFalse(owner.state.value.hasOwnedTunnel)
        assertEquals(null, owner.state.value.validationFailureCode)
    }

    @Test
    fun `lost validation retains owned tunnel and removes location until explicit stop`() = runTest {
        val session = FakeSession().apply { validated.complete(location) }
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver)
        runCurrent()
        assertTrue(owner.state.value.hasOwnedTunnel)

        session.validationFailure = true
        owner.networkChanged()
        runCurrent()
        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertTrue(owner.state.value.hasOwnedTunnel)
        assertEquals(null, owner.state.value.location)
        assertEquals(0, session.stops)

        owner.stop()
        runCurrent()
        assertEquals(1, session.stops)
        assertEquals(EnginePhase.IDLE, owner.state.value.phase)
        assertFalse(owner.state.value.hasOwnedTunnel)
    }

    @Test
    fun `incomplete cleanup retains owner and lets explicit stop retry`() = runTest {
        val session = FakeSession().apply {
            validated.complete(location)
            stopGate = CompletableDeferred(EngineStopResult(false))
        }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), FakeDriver().apply { opened.complete(session) })
        runCurrent()
        owner.stop()
        runCurrent()
        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertTrue(owner.state.value.hasOwnedTunnel)
        assertEquals(null, owner.state.value.location)

        session.stopGate = CompletableDeferred(EngineStopResult(true))
        owner.stop()
        runCurrent()
        assertEquals(2, session.stops)
        assertFalse(owner.state.value.hasOwnedTunnel)
        assertEquals(EnginePhase.IDLE, owner.state.value.phase)
    }

    @Test
    fun `poisoned cleanup blocks every following start`() = runTest {
        var poisoned = 0
        val session = FakeSession().apply {
            validated.complete(location)
            stopGate = CompletableDeferred(EngineStopResult(false, poisoned = true))
        }
        val driver = FakeDriver().apply { opened.complete(session) }
        val next = FakeDriver()
        val owner = VpnRuntimeOwner(backgroundScope, {}, { poisoned++ })
        owner.connect(profile("first"), driver)
        runCurrent()
        owner.stop()
        runCurrent()
        owner.connect(profile("next"), next)
        runCurrent()

        assertEquals(1, poisoned)
        assertEquals(0, next.starts)
        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
    }

    @Test
    fun `sanitized validation code survives released cleanup and ignored old-driver stop`() = runTest {
        val session = FakeSession().apply { validationError = UnknownHostException("private fixture endpoint and token") }
        val driver = FakeDriver().apply { opened.complete(session) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), driver, commandTicket = 501)
        runCurrent()

        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertFalse(owner.state.value.hasOwnedTunnel)
        assertEquals(EngineFailure.VALIDATION, owner.state.value.failure)
        assertEquals("DNS", owner.state.value.validationFailureCode)
        assertEquals(501L, owner.state.value.commandTicket)
        owner.stop(driver)
        runCurrent()
        assertEquals("DNS", owner.state.value.validationFailureCode)
        assertEquals(501L, owner.state.value.commandTicket)

        owner.stop()
        runCurrent()
        assertEquals(EnginePhase.IDLE, owner.state.value.phase)
        assertEquals(null, owner.state.value.validationFailureCode)
        assertEquals(null, owner.state.value.commandTicket)
    }

    @Test
    fun `health validation code is visible for retained tunnel and cleared after success`() = runTest {
        val session = FakeSession().apply { validated.complete(location) }
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), FakeDriver().apply { opened.complete(session) })
        runCurrent()
        session.healthError = SSLException("private fixture certificate and endpoint")
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertTrue(owner.state.value.hasOwnedTunnel)
        assertEquals("TLS", owner.state.value.validationFailureCode)
        assertEquals(null, owner.state.value.location)

        session.healthError = null
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
        assertEquals(null, owner.state.value.validationFailureCode)
    }

    @Test
    fun `published validation diagnostics are fixed names rather than exception contents`() = runTest {
        val sensitive = "private fixture endpoint credentials and response"
        val failures = listOf(
            UnknownHostException(sensitive) to "DNS",
            SSLException(sensitive) to "TLS",
            ValidationException(ValidationFailure.HTTP_STATUS) to "HTTP_STATUS",
            ValidationException(ValidationFailure.ROUTE_CHANGED) to "ROUTE_CHANGED",
            IllegalStateException(sensitive) to "UNEXPECTED",
        )
        for ((failure, expected) in failures) {
            val session = FakeSession().apply { validationError = failure }
            val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
            owner.connect(profile("fixture"), FakeDriver().apply { opened.complete(session) })
            runCurrent()
            assertEquals(EngineFailure.VALIDATION, owner.state.value.failure)
            assertEquals(expected, owner.state.value.validationFailureCode)
            assertFalse(owner.state.value.hasOwnedTunnel)
        }
    }

    @Test
    fun `queued replacement cannot relabel old connected owner before actor acknowledgement`() = runTest {
        val old = FakeSession().apply { validated.complete(location); stopGate = CompletableDeferred() }
        val first = FakeDriver().apply { opened.complete(old) }
        val next = FakeDriver()
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        owner.connect(profile("first"), first, commandTicket = 101)
        runCurrent()

        owner.connect(profile("next"), next, commandTicket = 202)
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
        assertEquals(101L, owner.state.value.commandTicket)
        assertEquals(location, owner.state.value.location)
        runCurrent()
        assertEquals(EnginePhase.STOPPING, owner.state.value.phase)
        assertEquals(101L, owner.state.value.commandTicket)
        assertEquals(0, next.starts)

        old.stopGate.complete(EngineStopResult(true))
        runCurrent()
        assertEquals(EnginePhase.CONNECTING, owner.state.value.phase)
        assertEquals(202L, owner.state.value.commandTicket)
        val replacement = FakeSession()
        next.opened.complete(replacement)
        runCurrent()
        assertEquals(EnginePhase.VALIDATING, owner.state.value.phase)
        assertEquals(202L, owner.state.value.commandTicket)
        replacement.validated.complete(location.copy(city = "Replacement city"))
        runCurrent()
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
        assertEquals(202L, owner.state.value.commandTicket)
    }

    @Test
    fun `same ticket duplicates deduplicate but a new ticket replaces even the same profile and driver`() = runTest {
        val old = FakeSession().apply { validated.complete(location); stopGate = CompletableDeferred() }
        val replacement = FakeSession().apply { validated.complete(location) }
        val driver = SequenceDriver(old, replacement)
        val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        val selected = profile("same")
        owner.connect(selected, driver, commandTicket = 37)
        runCurrent()
        owner.connect(selected, driver, commandTicket = 37)
        runCurrent()
        assertEquals(1, driver.starts)
        assertEquals(0, old.stops)

        owner.connect(selected, driver, commandTicket = 91)
        runCurrent()
        assertEquals(EnginePhase.STOPPING, owner.state.value.phase)
        assertEquals(37L, owner.state.value.commandTicket)
        assertEquals(1, old.stops)
        old.stopGate.complete(EngineStopResult(true))
        runCurrent()
        assertEquals(2, driver.starts)
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
        assertEquals(91L, owner.state.value.commandTicket)
    }

    @Test
    fun `pending owner ticket beats newer desired ticket and released error retains admitted ticket`() = runTest {
        for (oldTicket in listOf(null, 101L)) {
            val first = FakeDriver()
            val next = FakeDriver()
            val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
            owner.connect(profile("first"), first, commandTicket = oldTicket)
            runCurrent()
            assertEquals(EnginePhase.CONNECTING, owner.state.value.phase)
            assertEquals(oldTicket, owner.state.value.commandTicket)

            owner.connect(profile("next"), next, commandTicket = 202)
            runCurrent()
            assertEquals(EnginePhase.STOPPING, owner.state.value.phase)
            assertEquals(oldTicket, owner.state.value.commandTicket)
            first.opened.completeExceptionally(EngineException(EngineFailure.START))
            runCurrent()
            assertEquals(EnginePhase.CONNECTING, owner.state.value.phase)
            assertEquals(202L, owner.state.value.commandTicket)

            next.opened.completeExceptionally(EngineException(EngineFailure.PROFILE))
            runCurrent()
            assertEquals(EnginePhase.ERROR, owner.state.value.phase)
            assertFalse(owner.state.value.hasOwnedTunnel)
            assertEquals(202L, owner.state.value.commandTicket)
            owner.stop(first)
            runCurrent()
            assertEquals(202L, owner.state.value.commandTicket)
            owner.stop()
            runCurrent()
            assertEquals(EnginePhase.IDLE, owner.state.value.phase)
            assertEquals(null, owner.state.value.commandTicket)
        }
    }

    @Test
    fun `poisoned old startup cannot inherit a newer desired command ticket`() = runTest {
        var poisoned = 0
        val first = FakeDriver()
        val next = FakeDriver()
        val owner = VpnRuntimeOwner(backgroundScope, {}, { poisoned++ })
        owner.connect(profile("first"), first, commandTicket = 101)
        runCurrent()
        owner.connect(profile("next"), next, commandTicket = 202)
        runCurrent()
        first.opened.completeExceptionally(EngineException(EngineFailure.START, processPoisoned = true))
        runCurrent()
        assertEquals(1, poisoned)
        assertEquals(0, next.starts)
        assertEquals(EnginePhase.ERROR, owner.state.value.phase)
        assertEquals(101L, owner.state.value.commandTicket)
    }

    @Test
    fun `accepted tickets are idempotent through released and retained errors while legacy retries remain`() = runTest {
        for (retainTunnel in listOf(false, true)) {
            val old = FakeSession().apply {
                if (retainTunnel) validated.complete(location)
                else validationError = UnknownHostException("private fixture")
            }
            val driver = FakeDriver().apply { opened.complete(old) }
            val duplicate = FakeDriver()
            val owner = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
            owner.connect(profile("original"), driver, commandTicket = 101)
            runCurrent()
            if (retainTunnel) {
                old.validationError = UnknownHostException("private fixture")
                owner.networkChanged()
                runCurrent()
            }
            assertEquals(EnginePhase.ERROR, owner.state.value.phase)
            assertEquals(retainTunnel, owner.state.value.hasOwnedTunnel)
            val beforeDuplicate = owner.state.value
            owner.connect(profile("changed-payload"), duplicate, commandTicket = 101)
            runCurrent()
            assertEquals(beforeDuplicate, owner.state.value)
            assertEquals(0, duplicate.starts)
            assertEquals(1, driver.starts)

            val retry = FakeDriver().apply { opened.complete(FakeSession().apply { validated.complete(location) }) }
            owner.connect(profile("retry"), retry, commandTicket = 99)
            runCurrent()
            assertEquals(1, retry.starts)
            assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
            assertEquals(99L, owner.state.value.commandTicket)
        }

        val oldLegacy = FakeSession().apply { validated.complete(location) }
        val nextLegacy = FakeSession().apply { validated.complete(location) }
        val legacyDriver = SequenceDriver(oldLegacy, nextLegacy)
        val legacy = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        val selected = profile("legacy")
        legacy.connect(selected, legacyDriver)
        runCurrent()
        oldLegacy.validationError = UnknownHostException("private fixture")
        legacy.networkChanged()
        runCurrent()
        assertEquals(EnginePhase.ERROR, legacy.state.value.phase)
        legacy.connect(selected, legacyDriver)
        runCurrent()
        assertEquals(2, legacyDriver.starts)
        assertEquals(EnginePhase.CONNECTED, legacy.state.value.phase)
        assertEquals(null, legacy.state.value.commandTicket)
    }

    private fun profile(id: String) = VpnProfile(id, id, "VLESS", "{}")

    @Test fun `same-profile start queued during stop keeps the service and waits for the old tunnel release`() = runTest {
        val old = FakeSession().apply { validated.complete(location); stopGate = CompletableDeferred() }
        val next = FakeSession().apply { validated.complete(location) }
        val driver = SequenceDriver(old, next)
        var retired = 0
        val owner = VpnRuntimeOwner(backgroundScope, { retired++ }, { error("unexpected poison") })
        val selected = profile("same")
        owner.connect(selected, driver, commandTicket = 1)
        runCurrent()
        owner.stop()
        owner.connect(selected, driver, commandTicket = 3)
        runCurrent()
        assertEquals(1, driver.starts)
        assertEquals(1, old.stops)
        assertEquals(EnginePhase.STOPPING, owner.state.value.phase)
        assertEquals(0, retired)
        old.stopGate.complete(EngineStopResult(true))
        runCurrent()
        assertEquals(2, driver.starts)
        assertEquals(EnginePhase.CONNECTED, owner.state.value.phase)
        assertEquals(3L, owner.state.value.commandTicket)
        assertEquals(0, retired)
    }
    private class FakeDriver : EngineDriver {
        val opened = CompletableDeferred<EngineSession>()
        var starts = 0
        var aborts = 0
        override suspend fun open(profile: VpnProfile): EngineSession { starts++; return opened.await() }
        override fun abortPendingTun() { aborts++ }
    }
    private class SequenceDriver(vararg sessions: EngineSession) : EngineDriver {
        private val sessions = ArrayDeque(sessions.toList())
        var starts = 0
        override suspend fun open(profile: VpnProfile): EngineSession { starts++; return sessions.removeFirst() }
        override fun abortPendingTun() = Unit
    }
    private class FakeSession : EngineSession {
        val validated = CompletableDeferred<EngineLocation?>()
        var stopGate = CompletableDeferred(EngineStopResult(true))
        var stops = 0
        var validationFailure = false
        var validationDeadline = false
        var validationError: Throwable? = null
        var healthError: Throwable? = null
        override suspend fun validate(): EngineLocation? {
            validationError?.let { throw it }
            if (validationDeadline) withTimeout(1) { awaitCancellation() }
            if (validationFailure) throw EngineException(EngineFailure.VALIDATION)
            return validated.await()
        }
        override suspend fun refreshLocation(): EngineLocation = checkNotNull(validated.await())
        override suspend fun networkChanged() = Unit
        override suspend fun checkHealth(): Boolean { healthError?.let { throw it }; return false }
        override suspend fun stop(): EngineStopResult { stops++; return stopGate.await() }
    }
    private companion object { val location = EngineLocation("US", "Example", "United States", "203.0.113.10") }
}
