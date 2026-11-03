package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnNoticeStateTest {
    @Test fun parentNoticeIsReadyBeforeClosingSheetDisappears() {
        val notices = VpnNoticeState(elapsedRealtime = { 1_000L })
        val parent = Any()
        val sheet = Any()
        notices.attach(parent)
        notices.attach(sheet)
        notices.show("Applied")
        assertFalse(notices.draws(parent))
        assertTrue(notices.draws(sheet))
        notices.handoff(sheet, true)
        assertTrue(notices.draws(parent))
        notices.detach(sheet)
        assertTrue(notices.draws(parent))
    }

    @Test fun cancellingSheetCloseDoesNotExposeUnderlyingNotice() {
        val notices = VpnNoticeState(elapsedRealtime = { 1_000L })
        val parent = Any()
        val sheet = Any()
        notices.attach(parent)
        notices.attach(sheet)
        notices.handoff(sheet, true)
        notices.handoff(sheet, false)
        assertFalse(notices.draws(parent))
        assertTrue(notices.draws(sheet))
    }

    @Test fun noticeMotionStateSurvivesWindowTransferAndNewNoticeStartsClean() {
        val notices = VpnNoticeState(elapsedRealtime = { 1_000L })
        notices.show("First")
        val first = requireNotNull(notices.current)
        val motion = notices.motion(first.id)
        val oldAnimation = kotlinx.coroutines.Job()
        motion.motion = oldAnimation
        motion.offsetY = -18f
        motion.dismissing = true
        assertTrue(motion === notices.motion(first.id))
        assertEquals(-18f, notices.motion(first.id).offsetY, 0f)
        notices.show("Second")
        assertTrue(oldAnimation.isCancelled)
        val next = notices.motion(requireNotNull(notices.current).id)
        assertEquals(0f, next.offsetY, 0f)
        assertFalse(next.dismissing)
        notices.dismiss(first.id)
        assertEquals("Second", notices.current?.text)
    }

    @Test fun closingASheetDoesNotReplayTheSameNoticeEntranceOrLifetime() {
        val notices = VpnNoticeState(elapsedRealtime = { 1_000L })
        val parent = Any()
        val sheet = Any()
        notices.attach(parent)
        notices.attach(sheet)
        notices.show("Applied")
        val notice = requireNotNull(notices.current)
        assertFalse(notices.claimEntrance(parent, notice.id))
        assertTrue(notices.claimEntrance(sheet, notice.id))
        notices.detach(sheet)
        assertFalse(notices.claimEntrance(parent, notice.id))
        assertEquals(notice, notices.current)
        notices.show("Next")
        assertTrue(notices.claimEntrance(parent, requireNotNull(notices.current).id))
    }

    @Test fun expiredOldNoticeCannotDismissANewerMessage() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Old")
        val oldId = notices.current?.id
        notices.show("New")
        notices.dismiss(oldId)
        assertEquals("New", notices.current?.text)
    }

    @Test fun newFeedbackRetiresThePreviousRuntimeError() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.show("Copied")
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

    @Test fun clearingCurrentFeedbackDoesNotResurrectAnOlderRuntimeError() {
        val notices = VpnNoticeState(elapsedRealtime = { 0L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.clearTransient()
        assertEquals("Connection failed", notices.current?.text)
        notices.show("Copied")
        notices.clearTransient()
        assertNull(notices.current)
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

    @Test fun persistentWarningIsReplacedImmediatelyByNewFeedback() {
        val notices = VpnNoticeState(elapsedRealtime = { 1_000L })
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        assertNull(notices.current?.lifetime)
        notices.show("Copied")
        notices.dismiss()
        assertNull(notices.current)
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
