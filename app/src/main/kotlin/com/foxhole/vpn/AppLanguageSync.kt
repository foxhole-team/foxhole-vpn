package com.foxhole.vpn

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.IdentityHashMap
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val appLanguageLock = Mutex()
private val languagePorts = IdentityHashMap<VpnController, AndroidLanguagePort>()
private const val SYNC_PREFERENCES = "foxhole.app-language-sync"
private const val PENDING_PRIOR = "pending-prior-v2"
private const val PENDING_TARGET = "pending-target-v2"
private const val RESUME_PRIOR = "pending-resume-prior-v2"
private val COMPLETED_KEY = if (Build.VERSION.SDK_INT >= 33) "completed-api33-v2" else "completed-pre33-v2"

private fun decodeLanguage(name: String?): VpnLanguage? {
    if (name == null) return null
    return checkNotNull(VpnLanguage.entries.firstOrNull { it.name == name }) { "Unknown language journal value" }
}

private fun SharedPreferences.languageJournal(): AppLanguageJournal<VpnLanguage> {
    val prior = decodeLanguage(getString(PENDING_PRIOR, null))
    val target = decodeLanguage(getString(PENDING_TARGET, null))
    val resumePrior = decodeLanguage(getString(RESUME_PRIOR, null))
    check((prior == null) == (target == null) && (resumePrior == null || resumePrior == prior)) { "Incomplete language journal" }
    return AppLanguageJournal(decodeLanguage(getString(COMPLETED_KEY, null)),
        if (prior != null && target != null) PendingAppLanguage(prior, target, resumePrior) else null)
}

private fun platformLanguage(context: Context): VpnLanguage? {
    val tags = if (Build.VERSION.SDK_INT >= 33) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return null
        val locales = manager.applicationLocales
        if (locales.isEmpty) "" else locales[0].toLanguageTag()
    } else {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) "" else locales[0]?.toLanguageTag().orEmpty()
    }
    return languageFromPlatformTag(tags)
}

private suspend fun applyPlatformLanguage(context: Context, language: VpnLanguage) = withContext(Dispatchers.Main.immediate) {
    val tags = language.appLanguageTags()
    if (Build.VERSION.SDK_INT >= 33) {
        val manager = checkNotNull(context.getSystemService(LocaleManager::class.java))
        val locales = LocaleList.forLanguageTags(tags)
        if (manager.applicationLocales != locales) manager.applicationLocales = locales
    } else {
        val locales = LocaleListCompat.forLanguageTags(tags)
        if (AppCompatDelegate.getApplicationLocales() != locales) AppCompatDelegate.setApplicationLocales(locales)
    }
}

private class AndroidLanguagePort(context: Context, private val controller: VpnController) : AppLanguagePort<VpnLanguage> {
    private val context = context.applicationContext
    private var uncertainWriteSnapshot: WeakReference<Any>? = null
    val coordinator = AppLanguageCoordinator(this, VpnLanguage.SYSTEM, Build.VERSION.SDK_INT >= 33)

    override suspend fun readStored(): VpnLanguage? {
        if (!controller.appLanguageSettingsReady) return null
        val snapshot = controller.settings.value
        val uncertain = uncertainWriteSnapshot
        // A failed write cannot turn an unchanged cached snapshot into durable evidence.
        if (uncertain != null && snapshot === uncertain.get()) return null
        uncertainWriteSnapshot = null
        return snapshot.language
    }

    override suspend fun readPlatform(): VpnLanguage? = platformLanguage(context)
    override suspend fun readJournal(): AppLanguageJournal<VpnLanguage> = withContext(Dispatchers.IO) {
        context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE).languageJournal()
    }

    override suspend fun savePending(pending: PendingAppLanguage<VpnLanguage>) = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
        check(preferences.edit().putString(PENDING_PRIOR, pending.prior.name)
            .putString(PENDING_TARGET, pending.target.name).putString(RESUME_PRIOR, pending.resumePrior?.name).commit()) { "Language intent could not be saved" }
    }

    override suspend fun writeStored(expected: VpnLanguage, desired: VpnLanguage): Boolean {
        val snapshot = controller.settings.value
        if (snapshot.language != expected) return false
        uncertainWriteSnapshot = WeakReference<Any>(snapshot)
        val written = if (controller is DefaultVpnController) controller.updateAppLanguageIfCurrent(desired, expected)
            else if (controller.settings.value.language == expected) controller.updateAppLanguage(desired) else false
        if (written) uncertainWriteSnapshot = null
        return written
    }

    override suspend fun applyPlatform(desired: VpnLanguage) { applyPlatformLanguage(context, desired) }

    override suspend fun complete(desired: VpnLanguage, pending: PendingAppLanguage<VpnLanguage>?): Boolean = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.languageJournal().pending != pending || readStored() != desired || readPlatform() != desired) return@withContext false
        check(preferences.edit().putString(COMPLETED_KEY, desired.name)
            .remove(PENDING_PRIOR).remove(PENDING_TARGET).remove(RESUME_PRIOR).commit()) { "Language checkpoint could not be saved" }
        true
    }

    override suspend fun clearPending(pending: PendingAppLanguage<VpnLanguage>): Boolean = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.languageJournal().pending != pending) return@withContext false
        check(preferences.edit().remove(PENDING_PRIOR).remove(PENDING_TARGET).remove(RESUME_PRIOR).commit()) { "Stale language intent could not be cleared" }
        true
    }
}

private fun languagePort(context: Context, controller: VpnController): AndroidLanguagePort =
    languagePorts.getOrPut(controller) { AndroidLanguagePort(context, controller) }

internal suspend fun synchronizeVpnAppLanguage(context: Context, controller: VpnController): Boolean =
    appLanguageLock.withLock {
        try {
            controller.awaitReady()
            if (!controller.appLanguageSettingsReady) return@withLock false
            languagePort(context, controller).coordinator.synchronize()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("FoxHoleLocale", "Language synchronization deferred: ${error.javaClass.simpleName}")
            false
        }
    }

internal suspend fun selectVpnAppLanguage(context: Context, controller: VpnController, language: VpnLanguage): Boolean =
    appLanguageLock.withLock {
        controller.awaitReady()
        if (!controller.appLanguageSettingsReady) return@withLock false
        languagePort(context, controller).coordinator.select(language)
    }

internal suspend fun updateVpnAppSettings(controller: VpnController, updated: VpnSettings) =
    appLanguageLock.withLock {
        controller.awaitReady()
        if (controller.appLanguageSettingsReady) {
            val known = languagePorts[controller]?.readStored()
            if (languagePorts.containsKey(controller) && known == null) return@withLock
            controller.updateSettings(updated.copy(language = known ?: controller.settings.value.language))
        }
    }
