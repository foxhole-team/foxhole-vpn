package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterControlStage
import com.foxhole.vpn.router.RouterProfileSelection
import com.foxhole.vpn.router.RouterStorageMode
import com.foxhole.vpn.router.RouterWizardError
import com.foxhole.vpn.router.RouterWizardPath
import com.foxhole.vpn.router.RouterWizardStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterReviewBackendTest {
    @Test
    fun setupUsesRealWizardThenControlsEntirelyInMemory() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        var imported = false
        val wizard = backend.wizard(
            "Demo phone",
            exportProfile = { id ->
                RouterProfileSelection(id, "Demo phone", "hysteria2", "Demo node", null, null, DEMO_PROFILE_URI)
            },
            importRemoteProfile = { uri -> imported = uri.startsWith("hysteria2://"); true },
        )
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.confirmInstallProtocols()
        val password = "demo".toCharArray()
        wizard.login(password)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(RouterStorageMode.FLASH, wizard.state.value.storageMode)
        wizard.install()
        assertEquals(RouterWizardStage.NEW_PIN, wizard.state.value.stage)
        wizard.changePin("5678".toCharArray())
        wizard.options(
            wizard.state.value.options.copy(
                profileId = "phone-demo",
                publicIp = true,
                createRemoteProfile = true,
                homeAutodetection = true,
            ),
        )
        wizard.finish()
        assertEquals(null, wizard.state.value.error)
        assertEquals(RouterWizardStage.COMPLETE, wizard.state.value.stage)
        assertTrue(imported)
        assertFalse(wizard.state.value.remoteReady)
        val controls = backend.controls {}
        controls.open()
        assertEquals(RouterControlStage.CONNECTED, controls.state.value.stage)
        val server = requireNotNull(controls.state.value.snapshot).servers.first()
        controls.selectServer((server["id"] as JsonPrimitive).content)
        controls.setVpn(true)
        assertTrue(requireNotNull(controls.state.value.snapshot).connected)
        controls.setVpn(false)
        assertFalse(requireNotNull(controls.state.value.snapshot).connected)
        controls.setCountry("DE", false)
        assertEquals("DE", controls.state.value.preferences?.countryCode)
        controls.setHomeAutodetection(false)
        assertFalse(controls.state.value.homeAutomationEffective)
        backend.close()
    }

    @Test
    fun ramResourceScenarioAndCancellationDoNotInstall() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 200)
        backend.resourceMode(RouterStorageMode.RAM)
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.SETUP, "US")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.confirmInstallProtocols()
        wizard.login("demo".toCharArray())
        assertEquals(RouterStorageMode.RAM, wizard.state.value.storageMode)
        val installing = launch { wizard.install() }
        runCurrent()
        advanceTimeBy(100)
        wizard.close()
        runCurrent()
        assertTrue(installing.isCancelled)
        assertEquals(0, backend.installationCount)
        backend.close()
    }

    @Test
    fun pairRejectsWrongPinAndUsesChangedPinForLaterPairing() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("0000".toCharArray())
        assertEquals(RouterWizardError.PIN, wizard.state.value.error)
        wizard.login("1234".toCharArray())
        wizard.changePin("5678".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, wizard.state.value.stage)
        wizard.close()
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("5678".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, wizard.state.value.stage)
        backend.close()
    }
}
