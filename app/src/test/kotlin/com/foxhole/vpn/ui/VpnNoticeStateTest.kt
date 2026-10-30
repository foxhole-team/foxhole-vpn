package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnNoticeStateTest {
    @Test fun expiredOldNoticeCannotDismissANewerMessage() {
        val notices = VpnNoticeState()
        notices.show("Old")
        val oldId = notices.current?.id
        notices.show("New")
        notices.dismiss(oldId)
        assertEquals("New", notices.current?.text)
    }

    @Test fun transientFeedbackDoesNotEraseAnUnacknowledgedRuntimeError() {
        val notices = VpnNoticeState()
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.show("Copied")
        notices.dismiss()
        assertEquals("Connection failed", notices.current?.text)
        notices.dismiss()
        assertNull(notices.current)
    }

    @Test fun clearedRuntimeErrorCannotReturnAfterTransientFeedback() {
        val notices = VpnNoticeState()
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.show("Copied")
        notices.clearRuntime()
        notices.dismiss()
        assertNull(notices.current)
    }

    @Test fun openingAnImportCanClearOldFeedbackWithoutErasingTheRuntimeError() {
        val notices = VpnNoticeState()
        notices.show("Connection failed", error = true, persistent = true, runtime = true)
        notices.clearTransient()
        assertEquals("Connection failed", notices.current?.text)
        notices.show("Copied")
        notices.clearTransient()
        assertEquals("Connection failed", notices.current?.text)
    }
}
