package com.foxhole.vpn.engine

import com.foxhole.vpn.profile.VpnProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VpnApplicationPolicyRuntimeTest {
    @Test fun idlePolicyRequestCannotStartAVpnOrWriteSettings() = runTest {
        val runtime = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        var saved = false
        val apply = backgroundScope.launch { runCatching { runtime.applyApplicationPolicy(11, target, { true }) { saved = true } } }
        runCurrent()
        apply.join()
        assertFalse(saved)
        assertEquals(EnginePhase.IDLE, runtime.state.value.phase)
        assertFalse(runtime.state.value.hasOwnedTunnel)
    }

    @Test fun livePolicyChangeKeepsTheSameSessionAndTunnel() = runTest {
        val session = Session()
        val driver = Driver(session)
        val runtime = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        runtime.connect(profile, driver, 11)
        runCurrent()
        var saved = false
        val apply = backgroundScope.launch { runtime.applyApplicationPolicy(11, target, { true }) { saved = true } }
        runCurrent()
        assertTrue(saved)
        assertEquals(EnginePhase.VALIDATING, runtime.state.value.phase)
        assertTrue(runtime.state.value.hasOwnedTunnel)
        assertEquals(0, session.stops)
        session.release.complete(Unit)
        runCurrent()
        apply.join()
        assertEquals(EnginePhase.CONNECTED, runtime.state.value.phase)
        assertEquals(1, driver.starts)
        assertEquals(0, session.stops)
    }

    @Test fun policyFailureRetainsOwnedErrorUntilExplicitStop() = runTest {
        val session = Session().apply { failure = true; release.complete(Unit) }
        val driver = Driver(session)
        var retired = 0
        val runtime = VpnRuntimeOwner(backgroundScope, { retired++ }, { error("unexpected poison") })
        runtime.connect(profile, driver, 11)
        runCurrent()
        val apply = backgroundScope.launch { runCatching { runtime.applyApplicationPolicy(11, target, { true }) {} } }
        runCurrent()
        apply.join()
        assertEquals(EnginePhase.ERROR, runtime.state.value.phase)
        assertEquals(EngineFailure.APPLICATION_POLICY, runtime.state.value.failure)
        assertTrue(runtime.state.value.hasOwnedTunnel)
        assertEquals(0, retired)
        runtime.networkChanged()
        runCurrent()
        assertEquals(EnginePhase.ERROR, runtime.state.value.phase)
        runtime.stop()
        runCurrent()
        assertEquals(EnginePhase.IDLE, runtime.state.value.phase)
        assertEquals(1, session.stops)
        assertEquals(1, driver.starts)
    }

    @Test fun stopDuringPolicyWriteCannotPublishALateConnectedState() = runTest {
        val session = Session()
        val driver = Driver(session)
        val runtime = VpnRuntimeOwner(backgroundScope, {}, { error("unexpected poison") })
        runtime.connect(profile, driver, 11)
        runCurrent()
        var current = true
        var success = false
        val apply = backgroundScope.launch { success = runCatching {
            runtime.applyApplicationPolicy(11, target, { current }) {}
        }.isSuccess }
        runCurrent()
        current = false
        runtime.stop()
        runCurrent()
        session.release.complete(Unit)
        runCurrent()
        apply.join()
        assertFalse(success)
        assertEquals(EnginePhase.IDLE, runtime.state.value.phase)
        assertEquals(1, session.stops)
        assertEquals(1, driver.starts)
    }

    private class Driver(private val session: Session) : EngineDriver {
        var starts = 0
        override suspend fun open(profile: VpnProfile): EngineSession { starts++; return session }
        override fun abortPendingTun() = Unit
    }

    private class Session : EngineSession {
        val release = CompletableDeferred<Unit>()
        var stops = 0
        var failure = false
        override suspend fun validate(): EngineLocation? = null
        override suspend fun refreshLocation() = EngineLocation("US", "Fixture", "United States", "192.0.2.1")
        override suspend fun networkChanged() = Unit
        override suspend fun checkHealth() = false
        override suspend fun stop(): EngineStopResult { stops++; return EngineStopResult(true) }
        override suspend fun applyApplicationPolicy(scope: VpnApplicationScope, isCurrent: () -> Boolean,
            persist: suspend () -> Unit): EngineLocation? {
            persist()
            release.await()
            check(isCurrent())
            check(!failure)
            return null
        }
    }

    private companion object {
        val profile = VpnProfile("fixture", "Fixture", "VLESS", "{}")
        val target = VpnApplicationScope.Include(setOf("org.example.app"))
    }
}
