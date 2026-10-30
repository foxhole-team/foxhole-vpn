package com.foxhole.vpn.router

import com.foxhole.vpn.ConnectionCommandFence
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSocketOwnerRegistryTest {
    private class State {
        val commands = ConnectionCommandFence()
        var service = Any()
        var driver = Any()
        var ticket = commands.reserveStart()
        var allowed = true
        fun capture() = if (allowed) RouterSocketOwnerSnapshot(service, driver, ticket, commands.currentGeneration()) else null
    }

    private fun registry(state: State, protect: () -> Boolean = { true }) = RouterSocketOwnerRegistry(
        state.commands, capture = {
            assertTrue(Thread.holdsLock(state.commands))
            state.capture()
        }, protect = { _, _ ->
            assertFalse(Thread.holdsLock(state.commands))
            protect()
        },
    )

    @Test fun stableOwnerStaysApprovedThroughPhysicalBind() {
        val state = State(); val registry = registry(state)
        val protection = object : RouterSocketProtection {
            override fun protect(socket: Socket) = registry.protect(socket)
            override fun isCurrent(socket: Socket) = registry.isCurrent(socket)
        }
        Socket().use { socket ->
            assertEquals(socket, createRouterWifiSocket({ true }, protection, {}, { socket }))
            assertTrue(registry.isCurrent(socket))
        }
    }

    private fun refuseDuringBind(change: (State) -> Unit) {
        val state = State(); val registry = registry(state)
        val protection = object : RouterSocketProtection {
            override fun protect(socket: Socket) = registry.protect(socket)
            override fun isCurrent(socket: Socket) = registry.isCurrent(socket)
        }
        Socket().use { socket ->
            assertThrows(RouterVpnSocketUnavailable::class.java) {
                createRouterWifiSocket({ true }, protection, { change(state) }, { socket })
            }
            assertTrue(socket.isClosed)
            assertFalse(registry.isCurrent(socket))
        }
    }

    @Test fun aNewServiceDuringBindCannotReuseThePriorApproval() { refuseDuringBind { it.service = Any() } }
    @Test fun aNewDriverDuringBindCannotReuseThePriorApproval() { refuseDuringBind { it.driver = Any() } }
    @Test fun aNewServiceTicketDuringBindCannotReuseThePriorApproval() { refuseDuringBind { it.ticket++ } }
    @Test fun aNewCommandGenerationDuringBindCannotReuseThePriorApproval() { refuseDuringBind { it.commands.reserveStart() } }
    @Test fun ownerLossDuringBindRefusesEvenThoughVpnRemainsActive() { refuseDuringBind { it.allowed = false } }

    @Test fun changedOwnerDuringNativeProtectNeverApprovesTheSocket() {
        val state = State()
        val registry = registry(state) { state.commands.reserveStart(); true }
        Socket().use { socket -> assertFalse(registry.protect(socket)); assertFalse(registry.isCurrent(socket)) }
    }

    @Test fun aNativeProtectFailureCannotLeaveAnEarlierApproval() {
        val state = State(); var allowed = true
        val registry = registry(state) { allowed }
        Socket().use { socket ->
            assertTrue(registry.protect(socket))
            allowed = false
            assertFalse(registry.protect(socket))
            assertFalse(registry.isCurrent(socket))
        }
    }

    @Test fun closedSocketsReleaseBoundedApprovalCapacity() {
        val state = State(); val registry = registry(state)
        val sockets = List(129) { Socket() }
        try {
            sockets.take(128).forEach { assertTrue(registry.protect(it)) }
            assertFalse(registry.protect(sockets.last()))
            sockets.first().close()
            assertTrue(registry.protect(sockets.last()))
            assertFalse(registry.isCurrent(sockets.first()))
        } finally { sockets.forEach(Socket::close) }
    }

    @Test fun equalValuedServicesStillRequireTheSameIdentity() {
        class EqualService { override fun equals(other: Any?) = other is EqualService; override fun hashCode() = 1 }
        val state = State().also { it.service = EqualService() }
        val registry = registry(state)
        Socket().use { socket ->
            assertTrue(registry.protect(socket))
            state.service = EqualService()
            assertFalse(registry.isCurrent(socket))
        }
    }

    @Test fun unavailableOwnerNeverCallsNativeProtect() {
        val state = State().also { it.allowed = false }; var called = false
        val registry = registry(state) { called = true; true }
        Socket().use { socket -> assertFalse(registry.protect(socket)) }
        assertFalse(called)
    }
}
