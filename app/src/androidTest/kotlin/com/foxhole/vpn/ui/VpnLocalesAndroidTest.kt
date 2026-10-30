package com.foxhole.vpn.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.withVpnLocale
import com.foxhole.vpn.widget.widgetLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnLocalesAndroidTest {
    @Test fun englishResourcesAndWidgets() = checkLocale(VpnLanguage.EN, "en", "Settings", "Certificate verification is disabled")
    @Test fun russianResourcesAndWidgets() = checkLocale(VpnLanguage.RU, "ru", "Настройки", "Проверка сертификата отключена")
    @Test fun japaneseResourcesAndWidgets() = checkLocale(VpnLanguage.JA, "ja", "設定", "証明書の検証は無効です")
    @Test fun germanResourcesAndWidgets() = checkLocale(VpnLanguage.DE, "de", "Einstellungen", "Zertifikatsprüfung ist deaktiviert")
    @Test fun frenchResourcesAndWidgets() = checkLocale(VpnLanguage.FR, "fr", "Paramètres", "La vérification du certificat est désactivée")
    @Test fun spanishResourcesAndWidgets() = checkLocale(VpnLanguage.ES, "es", "Ajustes", "La verificación del certificado está desactivada")
    @Test fun chineseResourcesAndWidgets() = checkLocale(VpnLanguage.ZH, "zh-CN", "设置", "证书验证已禁用")

    private fun checkLocale(language: VpnLanguage, tag: String, settings: String, warning: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localized = context.withVpnLocale(language)
        val widget = widgetLocalizedContext(context, language)
        assertEquals(tag, localized.resources.configuration.locales[0].toLanguageTag())
        assertEquals(tag, widget.resources.configuration.locales[0].toLanguageTag())
        assertEquals(settings, localized.getString(R.string.vpn_settings))
        assertEquals(settings, widget.getString(R.string.vpn_settings))
        assertEquals(warning, localized.getString(R.string.vpn_insecure_title))
        assertEquals(warning, widget.getString(R.string.vpn_insecure_title))
        for (resource in intArrayOf(R.string.router_updates_uncertain, R.string.router_updates_rollback_unavailable,
            R.string.vpn_app_update_external_source, R.string.vpn_error_subscription_failed,
            R.string.widget_permission_denied)) {
            assertTrue(localized.getString(resource).isNotBlank())
            assertEquals(localized.getString(resource), widget.getString(resource))
        }
        val counts = localized.getString(R.string.vpn_import_result, 1, 2, 3)
        assertTrue(listOf("1", "2", "3").all(counts::contains))
        val memory = localized.getString(R.string.router_engines_memory_budget, "1 MiB", "2 MiB")
        assertTrue(memory.contains("1 MiB") && memory.contains("2 MiB"))
    }
}
