package com.foxhole.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnController
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.reportClipboardFocusFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ClipboardImportAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun pasteImportsImmediatelyWithoutEditorAndAClosedRequestCannotAttachLateConsent() {
        val controller = show()
        sources()
        withClipboard(ClipData.newPlainText("Owned first", Input).apply { addItem(ClipData.Item(SecondInput)) }) {
            paste()
            compose.onNodeWithText(text(R.string.vpn_profiles)).assertIsDisplayed()
            compose.onNodeWithText(Imported.name).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.vpn_import_result, 1, 0, 0)).assertIsDisplayed()
            noEditor()
            compose.runOnIdle {
                assertEquals(listOf(ImportCall(Input + "\n" + SecondInput, false)), controller.calls)
                unchangedTunnel(controller)
            }
            compose.onNodeWithText(text(R.string.vpn_close)).performClick()
            sources()
            val gate = CompletableDeferred<Unit>()
            compose.runOnIdle { controller.gate = gate; controller.result = VpnImportResult.NeedsInsecureTlsConsent }
            paste()
            awaitImports(controller, 2)
            compose.runOnIdle { assertEquals(2, controller.calls.size) }
            compose.onNodeWithText(text(R.string.vpn_close)).performClick()
            compose.onNodeWithContentDescription(text(R.string.vpn_settings)).performClick()
            compose.runOnIdle { gate.complete(Unit) }
            compose.onNodeWithText(text(R.string.vpn_settings)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertDoesNotExist()
            noEditor()
        }
    }

    @Test fun directPasteConsentCancelAndContinueKeepCapturedInputAndExternalDraftInvalidatesIt() {
        val external = mutableStateOf<String?>(null)
        val controller = show(external)
        compose.runOnIdle { controller.result = VpnImportResult.NeedsInsecureTlsConsent }
        sources()
        withClipboard(ClipData.newPlainText("Owned consent", Input)) {
            paste()
            compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
            noEditor()
            compose.onNodeWithText(text(R.string.vpn_cancel)).performClick()
            compose.onNodeWithText(text(R.string.vpn_import_from_clipboard)).assertIsDisplayed()
            compose.runOnIdle { assertEquals(listOf(ImportCall(Input, false)), controller.calls) }
            paste()
            compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertIsDisplayed()
            setClipboard(sensitive(ClipData.newPlainText("Changed clipboard", SecondInput)))
            compose.onNodeWithText(text(R.string.vpn_insecure_accept)).performClick()
            compose.onNodeWithText(text(R.string.vpn_profiles)).assertIsDisplayed()
            noEditor()
            compose.runOnIdle {
                assertEquals(listOf(ImportCall(Input, false), ImportCall(Input, false), ImportCall(Input, true)), controller.calls)
                unchangedTunnel(controller)
            }
            compose.onNodeWithText(text(R.string.vpn_close)).performClick()
            sources()
            val gate = CompletableDeferred<Unit>()
            compose.runOnIdle { controller.gate = gate }
            paste()
            awaitImports(controller, 4)
            compose.runOnIdle { assertEquals(4, controller.calls.size); external.value = "Owned external draft" }
            compose.onNodeWithText("Owned external draft").assertIsDisplayed()
            compose.runOnIdle { gate.complete(Unit) }
            compose.onNodeWithText("Owned external draft").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.vpn_insecure_title)).assertDoesNotExist()
            compose.runOnIdle { assertEquals(4, controller.calls.size); unchangedTunnel(controller) }
        }
    }

    @Test fun emptyClipboardShowsTopNoticeAndKeepsSourcesWithoutImporting() {
        val controller = show()
        sources()
        withClipboard(ClipData.newPlainText("Owned empty clipboard", "")) {
            paste()
            compose.onNodeWithText(text(R.string.vpn_clipboard_empty)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.vpn_import_from_clipboard)).assertIsDisplayed()
            noEditor()
            compose.runOnIdle { assertTrue(controller.calls.isEmpty()); unchangedTunnel(controller) }
        }
    }

    private fun show(external: MutableState<String?> = mutableStateOf(null)): Fixture {
        val controller = Fixture()
        compose.setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            VpnUiTheme(settings.theme) {
                VpnApp(controller, state, settings, VpnUiActions({}, {}, {}, { external.value = null }), external.value, null)
            }
        }
        return controller
    }

    private fun sources() { compose.onNodeWithContentDescription(text(R.string.vpn_no_profile)).performClick() }
    private fun paste() { compose.onNodeWithText(text(R.string.vpn_import_from_clipboard)).performClick() }
    private fun noEditor() { compose.onNodeWithText(text(R.string.vpn_import_text)).assertDoesNotExist() }
    private fun awaitImports(controller: Fixture, count: Int) {
        compose.waitUntil(5_000) { compose.runOnIdle { controller.calls.size == count } }
    }

    private fun sensitive(clip: ClipData): ClipData = clip.apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }

    private fun setClipboard(clip: ClipData) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { requireNotNull(context.getSystemService<ClipboardManager>()).setPrimaryClip(clip) }
    }

    private fun withClipboard(clip: ClipData, operation: () -> Unit) {
        val view = AtomicReference<View>()
        val roots = linkedSetOf<View>()
        val dialog = isDialog()
        val observedDialog = object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) { dialog.describeTo(description) }
            override fun matchesSafely(root: Root): Boolean {
                if (roots.size < 8) roots.add(root.decorView)
                return dialog.matches(root)
            }
        }
        val focusStarted = SystemClock.elapsedRealtime()
        try {
            onView(isRoot()).inRoot(observedDialog).check { root, _ -> view.set(root) }
            compose.waitUntil(5_000) { compose.runOnIdle { view.get()?.hasWindowFocus() == true } }
        } catch (failure: Exception) {
            reportClipboardFocusFailure(roots.toList() + listOfNotNull(view.get()), SystemClock.elapsedRealtime() - focusStarted)
            throw failure
        }
        var original: ClipData? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(requireNotNull(view.get()).hasWindowFocus())
            val clipboard = requireNotNull(context.getSystemService<ClipboardManager>())
            original = clipboard.primaryClip
            clipboard.setPrimaryClip(sensitive(clip))
        }
        try { operation() } finally { setClipboard(original ?: ClipData.newPlainText("", "")) }
    }

    private fun unchangedTunnel(controller: Fixture) {
        assertEquals(Current.id, controller.state.value.selectedProfileId)
        assertEquals(VpnPhase.CONNECTED, controller.state.value.phase)
        assertTrue(controller.state.value.hasOwnedTunnel)
        assertEquals(17L, controller.state.value.connectionId)
        assertEquals(0, controller.runtimeOrSelectionCalls)
    }

    private data class ImportCall(val input: String, val allowInsecure: Boolean) {
        override fun toString(): String = "ImportCall(bytes=${input.length}, allowInsecure=$allowInsecure)"
    }

    private class Fixture : VpnController {
        override val state = MutableStateFlow(VpnUiState(isReady = true, profiles = listOf(Current), selectedProfileId = Current.id,
            phase = VpnPhase.CONNECTED, hasOwnedTunnel = true, connectionId = 17L))
        override val settings = MutableStateFlow(VpnSettings())
        val calls = mutableListOf<ImportCall>()
        var result: VpnImportResult = VpnImportResult.Success(1)
        var gate: CompletableDeferred<Unit>? = null
        var runtimeOrSelectionCalls = 0
        override suspend fun awaitReady() = Unit
        override fun connectSelected() { runtimeOrSelectionCalls++ }
        override fun disconnect() { runtimeOrSelectionCalls++ }
        override fun selectProfile(id: String) { runtimeOrSelectionCalls++ }
        override fun refreshLocation() = Unit
        override suspend fun importProfiles(text: String, allowInsecureTls: Boolean): VpnImportResult {
            calls += ImportCall(text, allowInsecureTls)
            val response = if (allowInsecureTls) VpnImportResult.Success(1) else result
            val pending = gate
            pending?.await()
            if (response is VpnImportResult.Success) state.value = state.value.copy(profiles = listOf(Current, Imported))
            return response
        }
        override suspend fun refreshSubscription(profileId: String): VpnImportResult = error("No refresh expected")
        override suspend fun removeProfile(id: String) = error("No deletion expected")
        override suspend fun updateSettings(settings: VpnSettings) { this.settings.value = settings }
    }

    private companion object {
        const val Input = "vless://00000000-0000-4000-8000-000000000001@owned.invalid:443?security=tls#Owned"
        const val SecondInput = "vless://00000000-0000-4000-8000-000000000002@owned.invalid:443?security=tls#Second"
        val Current = VpnProfileSummary("current", "Owned active profile", "VLESS")
        val Imported = VpnProfileSummary("imported", "Owned imported profile", "VLESS")
    }
}
