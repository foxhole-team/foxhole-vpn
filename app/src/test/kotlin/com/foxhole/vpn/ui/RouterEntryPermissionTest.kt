package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

internal class RouterEntryPermissionTest {
    @Test fun manualInspectionWaitsForThePermissionActionToBeGranted() {
        var pending: (() -> Unit)? = null
        var networkCalls = 0
        requestRouterAccessForEntry({ true }, { pending = it }, { error("unavailable") }) { networkCalls++ }
        assertEquals(0, networkCalls)
        assertNotNull(pending)
        pending!!.invoke()
        assertEquals(1, networkCalls)
    }

    @Test fun denialDoesNotStartRouterTraffic() {
        var requests = 0
        var networkCalls = 0
        requestRouterAccessForEntry({ true }, { requests++ }, { error("unavailable") }) { networkCalls++ }
        assertEquals(1, requests)
        assertEquals(0, networkCalls)
    }

    @Test fun closingTheEntryWhilePermissionIsOpenPreventsAStaleOperation() {
        var current = true
        var pending: (() -> Unit)? = null
        var networkCalls = 0
        requestRouterAccessForEntry({ current }, { pending = it }, { error("unavailable") }) { networkCalls++ }
        current = false
        pending!!.invoke()
        assertEquals(0, networkCalls)
    }

    @Test fun missingOrExpiredEntryNeverBypassesThePermissionBoundary() {
        var unavailable = 0
        var requests = 0
        requestRouterAccessForEntry({ true }, null, { unavailable++ }) { error("network") }
        requestRouterAccessForEntry({ false }, { requests++ }, { error("unavailable") }) { error("network") }
        assertEquals(1, unavailable)
        assertEquals(0, requests)
    }

    @Test fun anAlreadyAllowedPlatformOrPermissionCanContinueImmediately() {
        var networkCalls = 0
        requestRouterAccessForEntry({ true }, { it() }, { error("unavailable") }) { networkCalls++ }
        assertEquals(1, networkCalls)
    }
}
