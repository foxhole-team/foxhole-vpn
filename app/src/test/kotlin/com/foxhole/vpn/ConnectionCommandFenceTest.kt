package com.foxhole.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCommandFenceTest {
    @Test
    fun `a reserved start blocks mutation before its UI phase is published`() {
        val fence = ConnectionCommandFence()
        val ticket = fence.reserveStart()
        assertFalse(fence.isCurrentWithoutPendingStart(ticket))
        fence.abandon(ticket)
        assertFalse(fence.isCurrentWithoutPendingStart(ticket))
        assertTrue(fence.isCurrentWithoutPendingStart(fence.currentGeneration()))
    }

    @Test
    fun `stop refuses delayed service start after permission or hydration wait`() {
        val fence = ConnectionCommandFence()
        val ticket = fence.reserveStart()
        val actions = mutableListOf<String>()
        fence.stop { actions += "stop" }
        assertFalse(fence.dispatch(ticket) { actions += "start" })
        assertEquals(listOf("stop"), actions)
        assertFalse(fence.hasPendingStart())
    }

    @Test
    fun `replacement admits only its current start and keeps cleanup ordered`() {
        val fence = ConnectionCommandFence()
        val first = fence.reserveStart()
        val second = fence.reserveStart()
        val actions = mutableListOf<String>()
        fence.stop(preserving = second) { actions += "stop-old" }
        assertFalse(fence.dispatch(first) { actions += "start-old" })
        assertTrue(fence.dispatch(second) { actions += "start-new" })
        assertEquals(listOf("stop-old", "start-new"), actions)
    }

    @Test
    fun `stop also revokes replacement still saving its selected profile`() {
        val fence = ConnectionCommandFence()
        val replacement = fence.reserveStart()
        val actions = mutableListOf<String>()
        fence.stop { actions += "user-stop" }
        fence.stop(preserving = replacement) { actions += "late-cleanup" }
        assertFalse(fence.applyCurrent(replacement) { actions += "launch-service" })
        assertEquals(listOf("user-stop"), actions)
    }

    @Test
    fun `source refresh finishing after stop cannot reserve a reconnect`() {
        val fence = ConnectionCommandFence()
        val beforeRefresh = fence.currentGeneration()
        fence.stop {}
        assertEquals(null, fence.reserveStartIfCurrent(beforeRefresh))
    }

    @Test
    fun `duplicate delivery cannot dispatch native start twice`() {
        val fence = ConnectionCommandFence()
        val ticket = fence.reserveStart()
        var starts = 0
        assertTrue(fence.dispatch(ticket) { starts++ })
        assertFalse(fence.dispatch(ticket) { starts++ })
        assertEquals(1, starts)
    }

    @Test
    fun `stale failure cannot clear a newer pending service request`() {
        val fence = ConnectionCommandFence()
        val previous = fence.reserveStart()
        val current = fence.reserveStart()
        assertFalse(fence.abandon(previous))
        assertTrue(fence.hasPendingStart())
        assertTrue(fence.abandon(current))
        assertFalse(fence.hasPendingStart())
    }
}
