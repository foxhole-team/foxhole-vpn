package com.foxhole.vpn.router.lab

import android.os.SystemClock
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.R
import com.foxhole.vpn.router.RouterApiError
import com.foxhole.vpn.router.RouterApiFailure
import com.foxhole.vpn.router.RouterInstallProtocol
import com.foxhole.vpn.router.RouterStorageMode
import com.foxhole.vpn.router.RouterWizardError
import com.foxhole.vpn.router.RouterWizardStage
import com.foxhole.vpn.router.WifiRouterWizardTransport
import com.foxhole.vpn.router.requireBoundedRouterJson
import com.foxhole.vpn.router.requireRouterLocalNetworkAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RouterWizardInstallAndroidTest {
    @get:Rule val ui = createAndroidComposeRule<RouterLabActivity>()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val app get() = ui.activity.application as RouterLabApplication
    private val wizard get() = requireNotNull(ui.activity.wizard)

    @Before fun configureOnlyTheReviewedIsolatedRuntime() = runBlocking {
        assertEquals("com.foxhole.vpn.routerlab", app.packageName)
        assertEquals("home-lab-20261011", arguments.getString("routerOwnedLab"))
        requireRouterLocalNetworkAccess(app)
        app.controller.awaitReady()
        assertFalse(app.controller.state.value.canDisconnect)
        val hash = publicHash("routerManifestSha256")
        ui.runOnUiThread { ui.activity.configureWizard(hash) }
        ui.waitForIdle()
    }

    @Test fun freshRouterUsesTheActualWizardAndVerifiedBundle() {
        setupToLogin(); loginRoot("rootPassword"); verifyProposal(); installFresh(); saveNewPin(); finishAndReadBack()
    }

    @Test fun freshBootstrapRequiresANewPinBeforeIndependentPairing() {
        val before = pairingId()
        setupToLogin(); loginRoot("rootPassword"); verifyProposal(); installFresh()
        assertEquals(before, pairingId())
        saveNewPin(); finishAndReadBack()
    }

    @Test fun anExistingRouterPinIsPreservedDuringTheActualWizard() {
        openEntry(); click(R.string.vpn_router_pair); identity()
        click(R.string.vpn_continue); awaitStage(RouterWizardStage.LOGIN)
        input(R.string.vpn_router_pin, privateInput("panelPin")); click(R.string.vpn_router_pair)
        awaitStage(RouterWizardStage.OPTIONS); finishAndReadBack()
        val saved = runBlocking { requireNotNull(app.store.read()) }
        try {
            runBlocking { transport().api(saved.identity).use { api -> api.unlock(privateInput("panelPin").toCharArray()).use { assertFalse(it.changePinRequired) } } }
        } finally { saved.clear() }
    }

    @Test fun wrongRootPasswordCannotInstallOrPersistPairing() {
        val before = pairingId()
        setupToLogin(); input(R.string.vpn_router_password, privateInput("wrongRootPassword")); click(R.string.vpn_router_check)
        ui.waitUntil(30000) { wizard.state.value.error != null }
        assertEquals(RouterWizardError.PASSWORD, wizard.state.value.error)
        assertEquals(before, pairingId()); assertFalse(wizard.state.value.stage == RouterWizardStage.COMPLETE)
    }

    @Test fun changedRootSshIdentityCannotReceiveThePassword() {
        val before = pairingId()
        setupToLogin(); marker("await-ssh-host-key-change"); rootStep("ssh-host-key-changed")
        input(R.string.vpn_router_password, privateInput("rootPassword")); click(R.string.vpn_router_check)
        ui.waitUntil(30000) { wizard.state.value.error != null }
        assertEquals(before, pairingId())
        assertTrue(wizard.state.value.error in setOf(RouterWizardError.SSH_UNAVAILABLE, RouterWizardError.IDENTITY))
    }

    @Test fun closingInspectionCancelsTheRealOwnedSshTransaction() {
        val before = pairingId()
        setupToLogin(); marker("await-inspection-hold"); rootStep("inspection-held")
        input(R.string.vpn_router_password, privateInput("rootPassword")); click(R.string.vpn_router_check)
        ui.waitUntil(5000) { wizard.state.value.busy }
        click(R.string.vpn_close); marker("inspection-canceled"); rootStep("inspection-released")
        ui.waitUntil(15000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertEquals(before, pairingId()); assertFalse(wizard.state.value.stage == RouterWizardStage.COMPLETE)
    }

    @Test fun closingInstallationCannotPublishUnverifiedPairing() {
        val before = pairingId()
        setupToLogin(); loginRoot("rootPassword"); verifyProposal(); click(R.string.vpn_router_install)
        ui.waitUntil(5000) { wizard.state.value.busy }
        marker("install-active"); click(R.string.vpn_close)
        assertEquals(before, pairingId()); assertFalse(wizard.state.value.stage == RouterWizardStage.COMPLETE)
        marker("install-canceled")
    }

    @Test fun anInterruptedInstallRequiresFreshInspectionBeforeRetry() {
        setupToLogin(); loginRoot("rootPassword"); verifyProposal()
        marker("await-install-fault"); rootStep("install-fault-ready")
        click(R.string.vpn_router_install)
        ui.waitUntil(120000) { wizard.state.value.error != null && !wizard.state.value.busy }
        assertEquals(RouterWizardStage.ADDRESS, wizard.state.value.stage)
        marker("await-install-fault-release"); rootStep("install-fault-released")
        click(R.string.vpn_router_check); identity(); click(R.string.vpn_continue)
        awaitStage(RouterWizardStage.PROTOCOLS); click(R.string.vpn_continue); awaitStage(RouterWizardStage.LOGIN)
        loginRoot("rootPassword"); verifyProposal(); click(R.string.vpn_router_install)
        ui.waitUntil(600000) { wizard.state.value.stage in setOf(RouterWizardStage.NEW_PIN, RouterWizardStage.LOGIN) && !wizard.state.value.busy }
        if (wizard.state.value.stage == RouterWizardStage.NEW_PIN) saveNewPin()
        else { input(R.string.vpn_router_pin, privateInput("panelPin")); click(R.string.vpn_router_pair); awaitStage(RouterWizardStage.OPTIONS) }
        finishAndReadBack()
    }

    @Test fun countrySelectionUsesTheHumanNameAndPreservesTheIsoContract() {
        openEntry(); click(R.string.vpn_router_pair); identity(); click(R.string.vpn_continue); awaitStage(RouterWizardStage.LOGIN)
        input(R.string.vpn_router_pin, privateInput("panelPin")); click(R.string.vpn_router_pair); awaitStage(RouterWizardStage.OPTIONS)
        selectCountry(if (wizard.state.value.options.countryCode == "DE") "FR" else "DE")
        selectCountry("NL")
        assertEquals("NL", wizard.state.value.options.countryCode)
        ui.onNodeWithText(countryLabel("NL")).assertExists()
        ui.onNodeWithText("NL", substring = false).assertDoesNotExist()
    }

    private fun setupToLogin() {
        openEntry(); click(R.string.vpn_router_setup); identity(); click(R.string.vpn_continue)
        awaitStage(RouterWizardStage.PROTOCOLS)
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), wizard.state.value.installSelection?.protocols)
        click(R.string.vpn_continue); awaitStage(RouterWizardStage.LOGIN)
    }
    private fun openEntry() { click(R.string.vpn_router) }
    private fun identity() {
        ui.waitUntil(60000) { wizard.state.value.stage == RouterWizardStage.IDENTITY || wizard.state.value.error != null && !wizard.state.value.busy }
        if (wizard.state.value.stage == RouterWizardStage.ADDRESS) {
            ui.onNode(hasSetTextAction() and hasText(ui.activity.getString(R.string.vpn_router_address))).performTextReplacement(requireNotNull(arguments.getString("routerLanAddress")))
            click(R.string.vpn_router_check); awaitStage(RouterWizardStage.IDENTITY)
        }
        awaitStage(RouterWizardStage.IDENTITY)
        if (wizard.state.value.path == com.foxhole.vpn.router.RouterWizardPath.SETUP) {
            val expected = publicHash("routerSshKeySha256").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(expected), wizard.state.value.fingerprint)
        } else {
            val expected = publicHash("routerCertificateSha256")
            assertEquals(expected, wizard.state.value.fingerprint.replace(" ", "").lowercase(Locale.ROOT))
        }
    }
    private fun loginRoot(field: String) { input(R.string.vpn_router_password, privateInput(field)); click(R.string.vpn_router_check); awaitStage(RouterWizardStage.INSTALL, 60000) }
    private fun verifyProposal() {
        assertEquals("25.12.5", wizard.state.value.openWrtVersion)
        val expected = if (arguments.getString("routerExpectedStorageMode") == "ram") RouterStorageMode.RAM else RouterStorageMode.FLASH
        assertEquals(expected, wizard.state.value.storageMode)
    }
    private fun installFresh() { click(R.string.vpn_router_install); awaitStage(RouterWizardStage.NEW_PIN, 600000) }
    private fun saveNewPin() {
        val pin = privateInput("newPanelPin")
        input(R.string.vpn_router_new_pin, pin); input(R.string.vpn_router_repeat_pin, pin); click(R.string.vpn_router_save)
        awaitStage(RouterWizardStage.OPTIONS)
    }
    private fun finishAndReadBack() {
        chooseCountry(); click(R.string.vpn_router_done); awaitStage(RouterWizardStage.COMPLETE, 60000)
        val saved = runBlocking { requireNotNull(app.store.read()) }
        try { runBlocking { transport().api(saved.identity).use { api -> api.unlockPairing(saved).use { assertTrue(it.token().startsWith("p1.")); api.state(it) } } } }
        finally { saved.clear() }
        assertFalse(app.controller.state.value.hasOwnedTunnel)
    }
    private fun chooseCountry() {
        if (wizard.state.value.options.countryCode == "NL") { assertEquals("NL", wizard.state.value.options.countryCode); return }
        selectCountry("NL")
    }
    private fun selectCountry(code: String) {
        val current = wizard.state.value.options.countryCode
        val label = if (current.isEmpty()) ui.activity.getString(R.string.router_control_choose_country) else countryLabel(current)
        ui.onNodeWithText(label).performScrollTo().performClick()
        ui.onNode(hasTextExactly(countryLabel(code)) and hasClickAction() and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        assertEquals(code, wizard.state.value.options.countryCode)
    }
    private fun countryLabel(code: String): String = code.map { Character.toChars(0x1f1e6 + it.code - 'A'.code).concatToString() }.joinToString("") +
        " " + Locale("", code).getDisplayCountry(ui.activity.resources.configuration.locales[0])
    private fun input(label: Int, value: String) { ui.onNode(hasSetTextAction() and hasText(ui.activity.getString(label))).performTextInput(value) }
    private fun click(label: Int) { ui.onNodeWithText(ui.activity.getString(label)).performClick() }
    private fun awaitStage(stage: RouterWizardStage, timeout: Long = 30000) = ui.waitUntil(timeout) { wizard.state.value.stage == stage && !wizard.state.value.busy }
    private fun pairingId(): String? = runBlocking { app.store.read()?.let { try { it.id } finally { it.clear() } } }
    private fun transport() = WifiRouterWizardTransport(requireNotNull(app.getSystemService(android.net.ConnectivityManager::class.java)), accessCheck = { requireRouterLocalNetworkAccess(app) })
    private fun publicHash(key: String) = requireNotNull(arguments.getString(key)).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun privateInput(key: String): String {
        val file = File(app.noBackupFilesDir, "router-lab-acceptance/auth.json")
        require(file.length() in 1..65536)
        val bytes = file.readBytes()
        return try {
            requireBoundedRouterJson(bytes, 8, 128)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Private input missing")
        } catch (_: Exception) { throw AssertionError("Private synthetic wizard input invalid") }
        finally { bytes.fill(0) }
    }
    private fun marker(step: String) { File(app.noBackupFilesDir, "router-wizard-stage.json").writeText(buildJsonObject { put("stage", step); put("elapsedMs", SystemClock.elapsedRealtime()) }.toString()) }
    private fun rootStep(expected: String) {
        ui.waitUntil(60000) {
            val file = File(app.noBackupFilesDir, "router-root-step.json")
            if (!file.isFile || file.length() !in 1..4096) false else {
                val value = Json.parseToJsonElement(file.readText()) as JsonObject
                (value["step"] as? JsonPrimitive)?.contentOrNull == expected &&
                    (value["bootId"] as? JsonPrimitive)?.contentOrNull == arguments.getString("routerExpectedBootId")
            }
        }
    }
}
