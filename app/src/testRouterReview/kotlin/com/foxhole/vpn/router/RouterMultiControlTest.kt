package com.foxhole.vpn.router

import com.foxhole.vpn.router.review.RouterReviewBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterMultiControlTest {
    private suspend fun pair(backend: RouterReviewBackend, adding: Boolean): String {
        val wizard = backend.wizard("Synthetic phone", { null }, { false })
        try {
            wizard.begin(RouterWizardPath.PAIR, null, addingNew = adding)
            wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
            wizard.login("1234".toCharArray()); wizard.changePin("5678".toCharArray())
            wizard.finish()
            check(wizard.state.value.stage == RouterWizardStage.COMPLETE)
            return requireNotNull(wizard.state.value.pairedId)
        } finally { wizard.close() }
    }

    @Test fun selectionAndForgetKeepTheOtherRouterPreferencesAndClearOldActions() = runTest {
        val backend = RouterReviewBackend(this, StandardTestDispatcher(testScheduler), stageDelayMs = 50)
        try {
            val first = pair(backend, false)
            val second = pair(backend, true)
            backend.preferenceStore.save(RouterControlPreferences(first, enabled = true, publicIpConfirmed = true))
            backend.preferenceStore.save(RouterControlPreferences(second))
            val control = backend.controls({}, { false })
            control.selectRouter(first)
            assertEquals(RouterControlStage.CONNECTED, control.state.value.stage)
            control.selectServer("server-000000000001")
            assertEquals("server-000000000001", control.state.value.selectedServerId)
            val pending = launch { control.setVpn(true) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertTrue(control.state.value.busy)
            control.selectRouter(second)
            runCurrent()
            assertTrue(pending.isCompleted)
            assertEquals(second, control.state.value.selectedRouterId)
            assertEquals(RouterControlStage.DISABLED, control.state.value.stage)
            assertNull(control.state.value.snapshot)
            assertTrue(backend.preferenceStore.read(first).publicIpConfirmed)
            assertFalse(backend.preferenceStore.read(second).publicIpConfirmed)
            control.unpair(second)
            assertEquals(first, control.state.value.selectedRouterId)
            assertEquals(1, control.state.value.savedRouters.size)
            assertEquals(RouterControlStage.OFFLINE, control.state.value.stage)
            assertTrue(backend.preferenceStore.read(first).publicIpConfirmed)
        } finally { backend.close() }
    }

    @Test fun theThreeRouterDemoKeepsDistinctPinnedIdentitiesAndReportsUnreachableInsteadOfAuthenticating() = runTest {
        val backend = RouterReviewBackend(this, StandardTestDispatcher(testScheduler), stageDelayMs = 0)
        try {
            val ids = listOf(pair(backend, false), pair(backend, true), pair(backend, true))
            assertEquals(3, backend.pairingStore.listSaved().routers.size)
            val entries = backend.pairingStore.readAll()
            try { assertEquals(3, entries.map { it.identity.certificateSha256 }.distinct().size) }
            finally { entries.forEach(RouterPairing::clear) }
            backend.preferenceStore.save(RouterControlPreferences(ids[2], enabled = true))
            backend.routerReachable(3, false)
            val control = backend.controls({}, { false })
            control.selectRouter(ids[2])
            assertEquals(RouterControlStage.OFFLINE, control.state.value.stage)
            assertNull(control.state.value.snapshot)
            assertEquals(ids[2], control.state.value.selectedRouterId)
            assertFalse(control.state.value.canAddRouter)
        } finally { backend.close() }
    }
}
