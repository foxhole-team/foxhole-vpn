package com.foxhole.vpn.review

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnImportResult
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.appupdates.VpnAppUpdatePhase
import com.foxhole.vpn.appupdates.VpnAppUpdateState
import com.foxhole.vpn.copyProfileClipboard
import com.foxhole.vpn.router.RouterPhoneProfileExport
import com.foxhole.vpn.router.RouterWizardCoordinator
import com.foxhole.vpn.router.RouterControlCoordinator
import com.foxhole.vpn.router.RouterStorageMode
import com.foxhole.vpn.ui.LocalVpnColors
import com.foxhole.vpn.ui.VpnApp
import com.foxhole.vpn.ui.VpnUiActions
import com.foxhole.vpn.ui.VpnUiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hosts the existing production composables with review-only in-memory boundaries. */
class ReviewActivity : AppCompatActivity() {
    private val review get() = application as ReviewApplication
    private val controller get() = review.controller
    private lateinit var wizard: RouterWizardCoordinator
    private lateinit var controls: RouterControlCoordinator
    private var importedText by mutableStateOf<String?>(null)
    private var actionNotice by mutableStateOf<String?>(null)
    private var demoStorageMode by mutableStateOf(RouterStorageMode.FLASH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        review.routerBackend.resourceMode(demoStorageMode)
        val export = RouterPhoneProfileExport(controller)
        wizard = review.routerBackend.wizard(
            deviceName = "Demo phone",
            exportProfile = export::selected,
            importRemoteProfile = { value -> controller.importProfiles(value) is VpnImportResult.Success },
        )
        controls = review.routerBackend.controls(copyProfile = {
            // Copy only the public synthetic example, never a entered PIN/password or backend token.
            withContext(Dispatchers.Main) {
                copyProfileClipboard(this@ReviewActivity, ReviewVpnController.DEMO_HY2)
            }
        }, importProfile = { value ->
            val result = controller.importProfiles(value)
            result is VpnImportResult.Success && result.rejected == 0 && result.added + result.updated > 0
        })
        enableEdgeToEdge()
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.language) {
                val tag = when (settings.language) {
                    VpnLanguage.SYSTEM -> ""
                    VpnLanguage.EN -> "en"
                    VpnLanguage.RU -> "ru"
                    VpnLanguage.JA -> "ja"
                    VpnLanguage.DE -> "de"
                    VpnLanguage.FR -> "fr"
                    VpnLanguage.ES -> "es"
                    VpnLanguage.ZH -> "zh-CN"
                }
                val locales = LocaleListCompat.forLanguageTags(tag)
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }
            VpnUiTheme(settings.theme, settings.fontStyle) {
                val colors = LocalVpnColors.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = colors.light
                        isAppearanceLightNavigationBars = colors.light
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        VpnApp(
                            controller = controller,
                            state = state,
                            settings = settings,
                            actions = VpnUiActions(
                                connect = controller::connectSelected,
                                chooseFile = ::useDemoImport,
                                systemSettings = ::explainOfflineAction,
                                consumeFile = { importedText = null },
                                consumeError = { actionNotice = null },
                                updateData = ::explainOfflineAction,
                                chooseQr = ::useDemoImport,
                                checkAppUpdate = ::explainOfflineAction,
                                appUpdateAction = ::explainOfflineAction,
                            ),
                            importedFileText = importedText,
                            externalError = actionNotice,
                            appUpdate = VpnAppUpdateState(phase = VpnAppUpdatePhase.DISABLED),
                            routerWizard = wizard,
                            routerControl = controls,
                            exportRouterProfile = { id -> review.routerBackend.literalDemoSelection(export.selected(id)) },
                            openRouterSetup = { explainOfflineAction() },
                            onRouterEntryRequest = { action -> action() },
                            onRouterHomeEnableRequest = { action -> action() },
                        )
                    }
                    Text(
                        text = stringResource(
                            if (demoStorageMode == RouterStorageMode.FLASH) R.string.review_demo_flash
                            else R.string.review_demo_ram,
                        ),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().clickable {
                            demoStorageMode = if (demoStorageMode == RouterStorageMode.FLASH) {
                                RouterStorageMode.RAM
                            } else {
                                RouterStorageMode.FLASH
                            }
                            review.routerBackend.resourceMode(demoStorageMode)
                        }.padding(vertical = 8.dp),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    private fun useDemoImport() {
        importedText = ReviewVpnController.DEMO_HY2
    }

    private fun explainOfflineAction() {
        actionNotice = getString(R.string.review_demo_action)
    }

    override fun onDestroy() {
        if (::wizard.isInitialized) wizard.close()
        if (::controls.isInitialized) controls.close()
        super.onDestroy()
    }
}
