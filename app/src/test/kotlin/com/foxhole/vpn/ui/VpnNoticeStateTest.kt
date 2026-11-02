package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnNoticeStateTest {
    @Test fun expiredOldNoticeCannotDismissANewerMessage() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Old")
        val oldId = notices.current?.id
        notices.show("New")
        notices.dismiss(oldId)
        assertEquals("New", notices.current?.text)
    }

    @Test fun transientFeedbackDoesNotEraseAnUnacknowledgedRuntimeError() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.show("Copied")
        notices.dismiss()
        assertEquals("Connection failed", notices.current?.text)
        notices.dismiss()
        assertNull(notices.current)
    }

    @Test fun clearedRuntimeErrorCannotReturnAfterTransientFeedback() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.show("Copied")
        notices.clearRuntime()
        notices.dismiss()
        assertNull(notices.current)
    }

    @Test fun openingAnImportCanClearOldFeedbackWithoutErasingTheRuntimeError() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.clearTransient()
        assertEquals("Connection failed", notices.current?.text)
        notices.show("Copied")
        notices.clearTransient()
        assertEquals("Connection failed", notices.current?.text)
    }

    @Test fun identicalNewMessagesHaveIndependentIdsAndDeadlines() {
        var now = 1_000L
        val notices = VpnNoticeState(elapsedRealtime = { now })
        notices.show("Copied")
        val old = requireNotNull(notices.current)
        now = 6_000L
        notices.show("Copied")
        val next = requireNotNull(notices.current)
        assertEquals(old.id + 1L, next.id)
        assertEquals(10_000L, old.lifetime?.remainingMillis(now))
        assertEquals(15_000L, next.lifetime?.remainingMillis(now))
        notices.dismiss(old.id)
        assertEquals(next.id, notices.current?.id)
    }

    @Test fun persistentRuntimeWarningsHaveNoCountdownEvenAfterTransientFeedback() {
        val notices = VpnNoticeState(elapsedRealtime = { 1_000L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        assertNull(notices.current?.lifetime)
        val runtimeId = notices.current?.id
        notices.show("Copied")
        notices.dismiss()
        assertEquals(runtimeId, notices.current?.id)
        assertNull(notices.current?.lifetime)
    }

    @Test fun emptyFeedbackDoesNotResetAnExistingCountdown() {
        var now = 1_000L
        val notices = VpnNoticeState(elapsedRealtime = { now })
        notices.show("Copied")
        val notice = notices.current
        now = 10_000L
        notices.show(" ")
        assertEquals(notice, notices.current)
        assertEquals(6_000L, notices.current?.lifetime?.remainingMillis(now))
    }
}
