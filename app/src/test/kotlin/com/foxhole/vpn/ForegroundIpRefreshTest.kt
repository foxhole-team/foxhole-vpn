package com.foxhole.vpn

import com.foxhole.vpn.refresh.ForegroundIpRefresh
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundIpRefreshTest {
    @Test fun onlyOneThirtyMinuteForegroundJobRunsAndBackgroundLeavesNoLookup() = runTest {
        var requests = 0
        val owner = ForegroundIpRefresh(backgroundScope, { requests++ })
        owner.setForeground(true)
        owner.setForeground(true)
        runCurrent()
        advanceTimeBy(30 * 60 * 1000L - 1)
        runCurrent()
        assertEquals(0, requests)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, requests)
        owner.setForeground(false)
        advanceTimeBy(90 * 60 * 1000L)
        runCurrent()
        assertEquals(1, requests)
        owner.setForeground(true)
        runCurrent()
        advanceTimeBy(30 * 60 * 1000L)
        runCurrent()
        assertEquals(2, requests)
    }
}
