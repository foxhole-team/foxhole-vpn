package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterWizardPath
import com.foxhole.vpn.router.RouterControlStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterReviewClientActionsTest {
    @Test
    fun clientCreationQrAndImportAreSeparateExplicitActions() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("1234".toCharArray())
        wizard.changePin("5678".toCharArray())
        wizard.options(wizard.state.value.options.copy(registerPhone = false, publicIp = true))
        wizard.finish()
        val imports = mutableListOf<String>()
        val control = backend.controls({}, { imports += it; true })
        control.open()
        control.createRemoteClient("Demo tablet")
        assertEquals(null, control.state.value.error)
        val client = requireNotNull(control.state.value.snapshot).clients.single()
        val id = (client["id"] as JsonPrimitive).content
        assertTrue(imports.isEmpty())
        var qr: String? = null
        control.showRemoteClient(id) { qr = it }
        assertEquals(DEMO_PROFILE_URI, qr)
        assertTrue(imports.isEmpty())
        control.importRemoteClient(id)
        assertEquals(listOf(DEMO_PROFILE_URI), imports)
        control.removeRemoteClient(id, requireNotNull(control.state.value.snapshot).revision)
        assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
        backend.close()
    }

    @Test
    fun invalidNameAndRefusedImportDoNotPretendSuccess() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("1234".toCharArray())
        wizard.changePin("5678".toCharArray())
        wizard.options(wizard.state.value.options.copy(registerPhone = false, publicIp = true))
        wizard.finish()
        val control = backend.controls({}, { false })
        control.open()
        control.createRemoteClient("я".repeat(25))
        assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
        assertTrue(control.state.value.error != null)
        control.createRemoteClient("Demo")
        val id = (requireNotNull(control.state.value.snapshot).clients.single()["id"] as JsonPrimitive).content
        control.importRemoteClient(id)
        assertTrue(control.state.value.error != null)
        assertEquals(1, requireNotNull(control.state.value.snapshot).clients.size)
        backend.close()
    }

    @Test
    fun closingBeforeExportAcknowledgmentCannotImportOrShowQr() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 100)
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("1234".toCharArray())
        wizard.changePin("5678".toCharArray())
        wizard.options(wizard.state.value.options.copy(registerPhone = false, publicIp = true))
        wizard.finish()
        var imported = false
        val control = backend.controls({}, { imported = true; true })
        control.open()
        control.createRemoteClient("Demo")
        val id = (requireNotNull(control.state.value.snapshot).clients.single()["id"] as JsonPrimitive).content
        val request = launch { control.importRemoteClient(id) }
        runCurrent()
        control.close()
        runCurrent()
        assertTrue(request.isCompleted)
        assertFalse(imported)
        control.open()
        var displayed = false
        val qrRequest = launch { control.showRemoteClient(id) { displayed = true } }
        runCurrent()
        control.close()
        runCurrent()
        assertTrue(qrRequest.isCompleted)
        assertFalse(displayed)
        backend.close()
    }

    @Test
    fun historicalCreationKeepsReadbackVisibleWithoutClaimingVpnAccess() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        backend.incomingConfigured(false)
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("1234".toCharArray())
        wizard.changePin("5678".toCharArray())
        wizard.options(wizard.state.value.options.copy(registerPhone = false, publicIp = true))
        wizard.finish()
        val control = backend.controls {}
        control.open()
        control.createRemoteClient("Demo template")
        assertEquals(RouterControlStage.CONNECTED, control.state.value.stage)
        assertTrue(control.state.value.clientCreationNeedsSetup)
        val client = requireNotNull(control.state.value.snapshot).clients.single()
        assertEquals(JsonPrimitive(false), client["provisioned"])
        control.open()
        assertTrue(control.state.value.clientCreationNeedsSetup)
        control.refresh()
        assertTrue(control.state.value.clientCreationNeedsSetup)
        control.createRemoteClient("Another template")
        assertEquals(1, requireNotNull(control.state.value.snapshot).clients.size)
        control.removeRemoteClient((client["id"] as JsonPrimitive).content, requireNotNull(control.state.value.snapshot).revision)
        assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
        backend.close()
    }
}
