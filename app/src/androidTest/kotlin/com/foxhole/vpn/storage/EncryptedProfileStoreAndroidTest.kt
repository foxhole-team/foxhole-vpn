package com.foxhole.vpn.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.vpn.profile.VpnProfile
import com.foxhole.vpn.DeviceLocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class EncryptedProfileStoreAndroidTest {
    private val contexts = mutableListOf<Context>()

    @After
    fun cleanupOwnedFixtures() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        contexts.forEach { context ->
            keys.deleteEntry("${context.packageName}.profiles.v1")
            context.noBackupFilesDir.deleteRecursively()
        }
    }
    @Test
    fun credentialsSurviveRestartWithoutPlaintextOnDisk() = runBlocking {
        val context = isolatedContext()
        val store = EncryptedProfileStore(context)
        val profile = VpnProfile("fixture", "Example", "VLESS", "secret-vpn-credential-fixture")
        store.update { it.copy(profiles = listOf(profile), selectedProfileId = profile.id) }

        val persisted = File(context.noBackupFilesDir, "vpn-profiles.v1").readBytes()
        assertFalse(persisted.toString(Charsets.ISO_8859_1).contains("secret-vpn-credential-fixture"))
        assertEquals(profile, EncryptedProfileStore(context).read().profiles.single())
    }

    @Test
    fun tamperedVaultIsNotTreatedAsAnEmptySuccessfulStore() = runBlocking {
        val context = isolatedContext()
        EncryptedProfileStore(context).update { it.copy(profiles = listOf(VpnProfile("fixture", "Example", "VLESS", "secret"))) }
        val file = File(context.noBackupFilesDir, "vpn-profiles.v1")
        val contents = file.readBytes()
        contents[contents.lastIndex] = (contents.last().toInt() xor 1).toByte()
        file.writeBytes(contents)

        var refused = false
        try { EncryptedProfileStore(context).read() } catch (_: Exception) { refused = true }
        assertTrue(refused)
        assertTrue(file.readBytes().contentEquals(contents))
    }

    @Test
    fun missingKeyPreservesCiphertextAndRefusesEmptyReset() = runBlocking {
        val context = isolatedContext()
        EncryptedProfileStore(context).update { it.copy(profiles = listOf(VpnProfile("fixture", "Example", "VLESS", "secret"))) }
        val file = File(context.noBackupFilesDir, "vpn-profiles.v1")
        val original = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("${context.packageName}.profiles.v1")

        var refused = false
        try { EncryptedProfileStore(context).read() } catch (_: Exception) { refused = true }
        assertTrue(refused)
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun lastKnownHomeSurvivesRestartEncryptedWithoutChangingProfilesOrSettings() = runBlocking {
        val context = isolatedContext()
        val profile = VpnProfile("fixture", "Example", "VLESS", "secret-vpn-credential-fixture")
        val before = EncryptedProfileStore(context).update {
            it.copy(profiles = listOf(profile), selectedProfileId = profile.id)
        }
        val home = StoredDevicePlace("DE", "CacheCityFixture", "CacheCountryFixture")
        EncryptedProfileStore(context).update { it.copy(lastKnownDevicePlace = home) }
        val ciphertext = File(context.noBackupFilesDir, "vpn-profiles.v1").readBytes()
        assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(home.city))
        assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(home.country))
        val after = EncryptedProfileStore(context).read()
        assertEquals(before.copy(lastKnownDevicePlace = home), after)
        assertEquals(DeviceLocationStatus.CACHED, after.lastKnownDevicePlace?.restore()?.status)
        val updated = EncryptedProfileStore(context).update { it.copy(selectedProfileId = null) }
        assertEquals(home, updated.lastKnownDevicePlace)
        assertEquals(home, EncryptedProfileStore(context).read().lastKnownDevicePlace)
    }

    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val identity = "vault_test_" + UUID.randomUUID().toString().replace("-", "")
        val directory = File(base.cacheDir, identity).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getPackageName(): String = base.packageName + "." + identity
        }.also(contexts::add)
    }
}
