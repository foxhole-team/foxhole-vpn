package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterControlCoordinator
import com.foxhole.vpn.router.RouterControlStage
import com.foxhole.vpn.router.RouterWizardPath
import com.foxhole.vpn.router.RouterProfileSelection
import com.foxhole.vpn.router.RouterDiscoveryKind
import com.foxhole.vpn.router.RouterWizardStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterReviewManagementTest {
    @Test
    fun automaticCountryUsesRouterWanAndPreservesExplicitOverride() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val wizard = backend.wizard("Demo phone", { null }, { true })
            wizard.begin(RouterWizardPath.PAIR, "DE")
            wizard.inspectAddress("192.168.1.1")
            wizard.acceptIdentity()
            wizard.login("1234".toCharArray())
            wizard.changePin("5678".toCharArray())
            assertEquals("NL", wizard.state.value.options.countryCode)
            wizard.finish()
            val control = backend.controls {}
            control.open()
            assertEquals("NL", control.state.value.routerCountryCode)
            assertTrue(control.state.value.preferences?.countryAuto == true)
            control.setCountry("DE", true)
            assertFalse(requireNotNull(control.state.value.preferences).countryAuto)
            control.open()
            assertEquals("DE", control.state.value.preferences?.countryCode)
            control.setCountryAuto(true)
            assertEquals("NL", control.state.value.preferences?.countryCode)
        } finally { backend.close() }
    }

    @Test
    fun remoteClientsExposeDifferentObservedStatuses() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val control = pairedControl(backend)
            control.setPublicIpConfirmed(true)
            control.createRemoteClient("Demo online")
            control.createRemoteClient("Demo offline")
            control.refreshClientStatus()
            assertNull(control.state.value.error)
            assertEquals(listOf(true, false), control.state.value.remoteClients.map { it.online })
        } finally { backend.close() }
    }

    @Test
    fun freshRouterDiscoveryInstallsThenRequiresOwnPinWithoutLoginPrompt() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1, initiallyInstalled = false)
        try {
            val wizard = backend.wizard("Demo phone", { null }, { true })
            wizard.begin(RouterWizardPath.PAIR, "NL")
            wizard.findRouter()
            assertEquals(RouterDiscoveryKind.SSH_AVAILABLE, wizard.state.value.discovery?.kind)
            assertTrue(wizard.state.value.fingerprint.isEmpty())
            wizard.installDiscovered()
            wizard.confirmInstallProtocols()
            wizard.login("DemoOnly".toCharArray())
            wizard.install()
            assertEquals(RouterWizardStage.NEW_PIN, wizard.state.value.stage)
            wizard.changePin("5678".toCharArray())
            wizard.finish()
            assertEquals(RouterWizardStage.COMPLETE, wizard.state.value.stage)
            wizard.begin(RouterWizardPath.PAIR, "NL")
            wizard.findRouter()
            assertEquals(RouterDiscoveryKind.INSTALLED, wizard.state.value.discovery?.kind)
            assertEquals(true, wizard.state.value.discovery?.pinConfigured)
        } finally { backend.close() }
    }

    @Test
    fun explicitResetRequiresExistingPinThenOwnPinAndClearsRouterProfiles() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val control = pairedControl(backend)
            val wizard = backend.wizard("Demo phone", { null }, { true })
            wizard.begin(RouterWizardPath.PAIR, "NL")
            wizard.findRouter()
            wizard.connectDiscovered(resetFoxHole = true)
            assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
            wizard.login("5678".toCharArray())
            assertNull(wizard.state.value.error)
            assertEquals(RouterWizardStage.NEW_PIN, wizard.state.value.stage)
            wizard.changePin("9012".toCharArray())
            wizard.finish()
            assertEquals(RouterWizardStage.COMPLETE, wizard.state.value.stage)
            control.open()
            assertNull(control.state.value.error)
            assertTrue(requireNotNull(control.state.value.snapshot).servers.isEmpty())
            assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
        } finally { backend.close() }
    }

    @Test
    fun sendingSelectedProtocolPreservesRouterSettingsAndCanBeRepeated() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val control = pairedControl(backend)
            val before = requireNotNull(control.state.value.snapshot)
            val selection = backend.literalDemoSelection(RouterProfileSelection(
                "phone-demo-vless", "Demo VLESS", "vless", "Demo VLESS", null, null, DEMO_VLESS_URI,
            ))
            control.sendSelection(selection)
            assertNull(control.state.value.error)
            val transferred = requireNotNull(control.state.value.snapshot)
            assertEquals(before.countries, transferred.countries)
            assertEquals(before.devices, transferred.devices)
            assertEquals(before.settings, transferred.settings)
            assertEquals(before.desiredServerId, transferred.desiredServerId)
            control.sendSelection(selection)
            assertNull(control.state.value.error)
            assertEquals(transferred.servers.size, requireNotNull(control.state.value.snapshot).servers.size)
        } finally { backend.close() }
    }

    @Test
    fun wholeProfileTransferIsVerifiedAndRepeatedSendDoesNotDuplicate() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val control = pairedControl(backend)
            control.sendProfile("Demo imported", DEMO_VLESS_URI)
            assertNull(control.state.value.error)
            val first = requireNotNull(control.state.value.snapshot)
            val id = requireNotNull(control.state.value.selectedServerId)
            var exported: String? = null
            control.showServer(id) { exported = it }
            assertEquals(DEMO_VLESS_URI, exported)
            control.sendProfile("Demo imported", DEMO_VLESS_URI)
            assertNull(control.state.value.error)
            assertEquals(first.servers.size, requireNotNull(control.state.value.snapshot).servers.size)
            assertFalse(requireNotNull(control.state.value.snapshot).connected)
        } finally { backend.close() }
    }

    @Test
    fun directIpIsRequiredBeforeCreatingRemoteVpnProfile() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val control = pairedControl(backend)
            control.createRemoteClient("Demo tablet")
            assertNotNull(control.state.value.error)
            assertTrue(requireNotNull(control.state.value.snapshot).clients.isEmpty())
            control.setPublicIpConfirmed(true)
            control.createRemoteClient("Demo tablet")
            assertNull(control.state.value.error)
            assertEquals(1, requireNotNull(control.state.value.snapshot).clients.size)
        } finally { backend.close() }
    }

    @Test
    fun confirmedPackageRemovalForgetsPairAfterAcknowledgement() = runTest {
        val backend = RouterReviewBackend(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        try {
            val control = pairedControl(backend)
            val id = requireNotNull(control.state.value.preferences).pairingId
            assertTrue(requireNotNull(control.state.value.snapshot).packageRemovalAvailable)
            control.refreshDevices()
            assertNull(control.state.value.error)
            assertTrue(control.state.value.lanDevices.any { it.online == false })
            control.unpair(id, deletePackage = true)
            assertNull(control.state.value.error)
            assertEquals(RouterControlStage.UNPAIRED, control.state.value.stage)
            assertNull(backend.pairingStore.read())
        } finally { backend.close() }
    }

    private suspend fun TestScope.pairedControl(backend: RouterReviewBackend): RouterControlCoordinator {
        val wizard = backend.wizard("Demo phone", { null }, { true })
        wizard.begin(RouterWizardPath.PAIR, "NL")
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        wizard.login("1234".toCharArray())
        wizard.changePin("5678".toCharArray())
        wizard.finish()
        return backend.controls {}.also { it.open() }
    }
}
