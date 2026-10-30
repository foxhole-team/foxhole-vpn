package com.foxhole.vpn.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.ConnectionCommandFence
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.engine.EngineDriver
import com.foxhole.vpn.engine.EngineException
import com.foxhole.vpn.engine.EngineFailure
import com.foxhole.vpn.engine.EnginePhase
import com.foxhole.vpn.engine.EngineSession
import com.foxhole.vpn.engine.VpnRuntimeOwner
import com.foxhole.vpn.profile.VpnProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerServiceRevocationAndroidTest {
    private val app get() = ApplicationProvider.getApplicationContext<VpnApplication>()
    private val controller get() = app.runtimeController
    private val runtime get() = field(controller, "runtime") as VpnRuntimeOwner
    private val commands get() = field(controller, "commands") as ConnectionCommandFence

    @Test
    fun retiredAcceptedServicePreservesTerminalFailure(): Unit = runBlocking {
        requireIdleDebugApp()
        val service = VpnTunnelService()
        val ticket = commands.reserveStart()
        try {
            assertTrue(commands.dispatch(ticket) {
                service.recordAcceptedCommand(ticket)
                runtime.connect(VpnProfile("owned-failure", "Owned failure", "VLESS", "{}"), object : EngineDriver {
                    override suspend fun open(profile: VpnProfile): EngineSession = throw EngineException(EngineFailure.PROFILE)
                    override fun abortPendingTun() = Unit
                }, commandTicket = ticket)
            })
            withTimeout(5_000) { runtime.state.first { it.phase == EnginePhase.ERROR && it.failure == EngineFailure.PROFILE } }
            withTimeout(5_000) { controller.state.first { it.phase == VpnPhase.ERROR && it.message != null } }
            assertFalse(runtime.state.value.hasOwnedTunnel)
            assertEquals(ticket, runtime.state.value.commandTicket)

            controller.serviceRevoked(service)

            val replaced = withTimeoutOrNull(750) {
                runtime.state.first { it.phase != EnginePhase.ERROR || it.failure != EngineFailure.PROFILE }
            }
            assertNull("An already released service cleared the terminal failure", replaced)
            assertEquals(ticket, runtime.state.value.commandTicket)
            assertEquals(VpnPhase.ERROR, controller.state.value.phase)
            assertTrue(controller.state.value.message != null)
        } finally {
            controller.disconnect()
            withTimeout(5_000) { controller.state.first { it.phase == VpnPhase.IDLE && !it.hasOwnedTunnel } }
        }
    }

    @Test
    fun revokedPendingServiceRefusesItsDelayedStart(): Unit = runBlocking {
        requireIdleDebugApp()
        val service = VpnTunnelService()
        val ticket = commands.reserveStart()
        service.recordPendingCommand(ticket)
        try {
            @Suppress("UNCHECKED_CAST")
            val ui = field(controller, "mutable") as MutableStateFlow<VpnUiState>
            commands.applyCurrent(ticket) {
                ui.value = ui.value.copy(phase = VpnPhase.CONNECTING, location = null, message = null)
            }
            assertTrue(commands.hasPendingStart())
            assertEquals(VpnPhase.CONNECTING, controller.state.value.phase)
            assertEquals(EnginePhase.IDLE, runtime.state.value.phase)

            controller.serviceRevoked(service)

            assertFalse(commands.hasPendingStart())
            assertFalse(commands.isCurrent(ticket))
            assertFalse(commands.dispatch(ticket) { error("Revoked command reached the native owner") })
            assertEquals(EnginePhase.IDLE, runtime.state.value.phase)
            withTimeout(5_000) { controller.state.first { it.phase == VpnPhase.IDLE && !it.hasOwnedTunnel } }
            assertFalse(controller.state.value.canDisconnect)
        } finally {
            controller.disconnect()
            withTimeout(5_000) { controller.state.first { it.phase == VpnPhase.IDLE && !it.hasOwnedTunnel } }
        }
    }

    private suspend fun requireIdleDebugApp() {
        assertEquals("com.foxhole.vpn.debug", app.packageName)
        controller.awaitReady()
        assertEquals(VpnPhase.IDLE, controller.state.value.phase)
        assertFalse(controller.state.value.canDisconnect)
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) ?: error("Owned controller field missing")
}
