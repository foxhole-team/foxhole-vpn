package com.foxhole.vpn.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLicenseState
import com.foxhole.vpn.VpnTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLicenseAgreementAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun loadingAndUncheckedAgreementNeverComposeTheApp() {
        val state = mutableStateOf(VpnLicenseState())
        var confirmations = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnLicenseGate(state.value, onAccept = { confirmations++ }, onClose = {}) { Text(APP_CONTENT) }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_license_loading)).assertIsDisplayed()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        compose.runOnIdle { state.value = VpnLicenseState(loaded = true) }
        compose.onNodeWithText(text(R.string.vpn_continue)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().assertIsOff()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirmations) }
    }

    @Test fun checkedContinueWaitsForCommittedConsentAndAllowsStorageRetry() {
        val state = mutableStateOf(VpnLicenseState(loaded = true))
        val confirmations = mutableListOf<Boolean>()
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnLicenseGate(state.value, onAccept = { confirmations += it }, onClose = {}) { Text(APP_CONTENT) }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText(text(R.string.vpn_continue)).assertIsEnabled().performClick()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(true), confirmations)
            state.value = state.value.copy(saving = true)
        }
        compose.onNodeWithText(text(R.string.vpn_working)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.vpn_license_accept)).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(saving = false, storageError = true) }
        compose.onNodeWithText(text(R.string.vpn_license_storage_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_continue)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(true, true), confirmations)
            state.value = VpnLicenseState(loaded = true, accepted = true)
        }
        compose.onNodeWithText(APP_CONTENT).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_license_title)).assertDoesNotExist()
    }

    @Test fun componentLicensesAndBackCannotAcceptTheAgreement() {
        var confirmations = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnLicenseGate(VpnLicenseState(loaded = true), onAccept = { confirmations++ }, onClose = {}) {
                    Text(APP_CONTENT)
                }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_license_open_licenses)).performScrollTo().performClick()
        compose.onNodeWithText("FoxHole Core").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("flag-icons").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.vpn_continue)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, confirmations) }
    }

    @Test fun restoringCheckedUiStateCannotRestoreUncommittedAcceptance() {
        val restoration = StateRestorationTester(compose)
        var confirmations = 0
        var closed = 0
        restoration.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnLicenseGate(VpnLicenseState(loaded = true), onAccept = { confirmations++ }, onClose = { closed++ }) {
                    Text(APP_CONTENT)
                }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().assertIsOn()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_close)).performClick()
        compose.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, closed)
        }
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
    }

    @Test fun compactLargeTextKeepsAgreementScrollableAndContinueReachable() {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
                    Box(Modifier.width(260.dp).height(420.dp)) {
                        VpnLicenseGate(VpnLicenseState(loaded = true), onAccept = {}, onClose = {}) { Text(APP_CONTENT) }
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.vpn_continue)).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.vpn_license_open_licenses)).performScrollTo().performClick()
        compose.onNodeWithText("flag-icons").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_close)).assertIsDisplayed()
    }

    @Test fun englishAndRussianReuseGuardsFiveTermsWithTheVpnName() {
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val localized = context.createConfigurationContext(configuration)
            val body = localized.getString(R.string.vpn_license_body)
            assertEquals(5, body.split("\n\n").size)
            assertFalse(body.contains("\\n"))
            assertFalse(body.contains("FoxHole Guard"))
            assertEquals(1, Regex("FoxHole VPN").findAll(body).count())
        }
    }

    private companion object {
        const val APP_CONTENT = "License test app content"
    }
}
