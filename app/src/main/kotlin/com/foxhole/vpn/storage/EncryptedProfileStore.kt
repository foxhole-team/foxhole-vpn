package com.foxhole.vpn.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.profile.VpnProfile
import com.foxhole.vpn.refresh.AutomaticRefreshLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class ProfileStoreState(
    val schema: Int = 1,
    val profiles: List<VpnProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val settings: VpnSettings = VpnSettings(),
    val automaticRefresh: AutomaticRefreshLedger = AutomaticRefreshLedger(),
    val lastKnownDevicePlace: StoredDevicePlace? = null,
)

internal class EncryptedProfileStore(context: Context) {
    private val lock = Mutex()
    private val file = AtomicFile(File(context.noBackupFilesDir, "vpn-profiles.v1"))
    private val alias = "${context.packageName}.profiles.v1"
    private val aad = "$alias:1".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private var loaded: ProfileStoreState? = null

    suspend fun read(): ProfileStoreState = withContext(Dispatchers.IO) {
        lock.withLock { readLocked() }
    }

    suspend fun update(transform: (ProfileStoreState) -> ProfileStoreState): ProfileStoreState = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = transform(readLocked())
            require(next.schema == 1 && next.profiles.size <= 256 && next.automaticRefresh.subscriptions.size <= 256)
            val plaintext = json.encodeToString(next).toByteArray(Charsets.UTF_8)
            require(plaintext.size <= MAX_BYTES)
            val envelope = try { encrypt(plaintext) } finally { plaintext.fill(0) }
            val stream = file.startWrite()
            try {
                stream.write(envelope)
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                file.failWrite(stream)
                throw failure
            }
            loaded = next
            next
        }
    }

    private fun readLocked(): ProfileStoreState {
        loaded?.let { return it }
        val recovery = File(file.baseFile.path + ".bak")
        if (!file.baseFile.exists() && !recovery.exists()) return ProfileStoreState().also { loaded = it }
        val source = if (recovery.exists()) recovery else file.baseFile
        require(source.length() in 1..(MAX_BYTES + 64).toLong())
        val raw = file.readFully()
        val buffer = ByteBuffer.wrap(raw)
        require(buffer.remaining() > 32 && buffer.int == MAGIC)
        val nonceLength = buffer.get().toInt()
        require(nonceLength == 12 && buffer.remaining() > nonceLength + 16)
        val nonce = ByteArray(nonceLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext)
        val state = try { json.decodeFromString<ProfileStoreState>(plaintext.toString(Charsets.UTF_8)) }
        finally { plaintext.fill(0) }
        require(state.schema == 1 && state.profiles.size <= 256 && state.automaticRefresh.subscriptions.size <= 256)
        require(state.profiles.map { it.id }.distinct().size == state.profiles.size)
        return state.also { loaded = it }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        val nonce = cipher.iv
        require(nonce.size == 12)
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(5 + nonce.size + encrypted.size)
            .putInt(MAGIC).put(nonce.size.toByte()).put(nonce).put(encrypted).array()
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "credential_key_unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val MAGIC = 0x46565031
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
