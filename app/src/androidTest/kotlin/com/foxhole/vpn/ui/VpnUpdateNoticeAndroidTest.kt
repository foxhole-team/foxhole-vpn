package com.foxhole.vpn.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLicenseState
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnUpdateNoticeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnUpdateNoticeAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun noticeNeverBypassesLicenseAndAppearsAsAnOverlayAfterAcceptance() {
        val license = mutableStateOf(VpnLicenseState(loaded = true))
        val notice = mutableStateOf(VpnUpdateNoticeState(loaded = true))
        var accepted = 0
        var acknowledged = 0
        var closed = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnAppStartup(license.value, notice.value,
                    onAcceptLicense = { if (it) accepted++ },
                    onAcknowledgeNotice = {
                        acknowledged++
                        notice.value = notice.value.copy(acknowledged = true)
                    }, onClose = { closed++ }) { Text(APP_CONTENT) }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_license_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertDoesNotExist()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.vpn_license_accept)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.vpn_continue)).performClick()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertDoesNotExist()
        compose.onNodeWithText(APP_CONTENT).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, accepted)
            assertEquals(0, acknowledged)
            license.value = license.value.copy(accepted = true)
        }
        compose.onNodeWithText(APP_CONTENT).assertExists()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_formats)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_imports)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_router)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_close)).performClick()
        compose.onNodeWithText(APP_CONTENT).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, acknowledged)
            assertEquals(0, closed)
        }
    }

    @Test fun loadingTheInformationalNoticeDoesNotBlockTheLicensedApp() {
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnAppStartup(VpnLicenseState(loaded = true, accepted = true), VpnUpdateNoticeState(),
                    onAcceptLicense = {}, onAcknowledgeNotice = {}, onClose = {}) { Text(APP_CONTENT) }
            }
        }
        compose.onNodeWithText(APP_CONTENT).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertDoesNotExist()
    }

    @Test fun shortListSurvivesRestorationWithoutDetailsAndBackAcknowledges() {
        val restoration = StateRestorationTester(compose)
        val notice = mutableStateOf(VpnUpdateNoticeState(loaded = true))
        var acknowledged = 0
        var closed = 0
        restoration.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnAppStartup(VpnLicenseState(loaded = true, accepted = true), notice.value,
                    onAcceptLicense = {},
                    onAcknowledgeNotice = {
                        acknowledged++
                        notice.value = notice.value.copy(acknowledged = true)
                    }, onClose = { closed++ }) { Text(APP_CONTENT) }
            }
        }
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNodeWithText("Подробнее").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.vpn_release_notes_formats)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_imports)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_router)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, acknowledged) }
        Espresso.pressBack()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertDoesNotExist()
        compose.onNodeWithText(APP_CONTENT).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, acknowledged)
            assertEquals(0, closed)
        }
    }

    @Test fun failedDismissalReopensWithErrorAndCanBeRetriedWithoutLeavingTheApp() {
        val notice = mutableStateOf(VpnUpdateNoticeState(loaded = true))
        var acknowledgements = 0
        compose.setContent {
            VpnUiTheme(VpnTheme.DARK) {
                VpnAppStartup(VpnLicenseState(loaded = true, accepted = true), notice.value,
                    onAcceptLicense = {}, onAcknowledgeNotice = {
                        acknowledgements++
                        notice.value = notice.value.copy(saving = acknowledgements == 1, acknowledged = acknowledgements > 1)
                    }, onClose = {}) { Text(APP_CONTENT) }
            }
        }
        compose.onNodeWithText(text(R.string.vpn_close)).performClick()
        compose.runOnIdle {
            assertEquals(1, acknowledgements)
            notice.value = notice.value.copy(saving = false, storageError = true, retryGeneration = 1)
        }
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_release_notes_storage_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(APP_CONTENT).assertExists()
        compose.onNodeWithText(text(R.string.vpn_close)).performClick()
        compose.onNodeWithText(text(R.string.vpn_release_notes_title)).assertDoesNotExist()
        compose.onNodeWithText(APP_CONTENT).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, acknowledgements) }
    }

    private companion object {
        const val APP_CONTENT = "Update notice test app content"
    }
}
