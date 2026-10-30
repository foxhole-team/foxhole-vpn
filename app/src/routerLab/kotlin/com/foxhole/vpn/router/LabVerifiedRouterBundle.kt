package com.foxhole.vpn.router

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.foxhole.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

internal class LabVerifiedRouterBundle(context: Context, private val manifestSha256: String) : RouterReleaseAssetSource {
    private val directory = File(context.noBackupFilesDir, "router-release-bundle")
    @Volatile private var loadedProof: JsonObject? = null

    init {
        require(BuildConfig.BUILD_TYPE == "routerLab" && context.packageName == "com.foxhole.vpn.routerlab")
        require(manifestSha256.matches(Regex("[a-f0-9]{64}")))
    }

    override suspend fun read(name: String, maximumBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        proof()
        readPrivateFile(name, maximumBytes)
    }

    fun commandSource(client: RouterSshClient, payload: RouterSetupPayload, proposal: RouterInstallProposal): RouterInstallCommandSource {
        val proof = proof()
        verifyLabRouterBundleBinding(proof, payload, proposal)
        val actual = routerInstallDocument(client.read(RouterReadCommand.RELEASE_BUNDLE_FACTS), 4096)
        verifyLabRouterBundleReadback(actual, proof, manifestSha256)
        return VerifiedLocalBundle()
    }

    private class VerifiedLocalBundle : RouterInstallCommandSource() {
        override fun arguments(): List<String> = listOf("--bundle", "/mnt/foxhole-release")
    }

    private fun proof(): JsonObject {
        loadedProof?.let { return it }
        return synchronized(this) {
            loadedProof ?: routerInstallDocument(readPrivateFile("fixture-proof.json", 32768), 32768).also {
                loadedProof = validateLabRouterBundleProof(it, manifestSha256)
            }
        }
    }

    private fun readPrivateFile(name: String, maximum: Int): ByteArray {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,191}")) && maximum in 1..1048576)
        val parent = Os.lstat(directory.path)
        require(OsConstants.S_ISDIR(parent.st_mode) && parent.st_uid == Process.myUid() && (parent.st_mode and 511) == 448)
        val file = File(directory, name)
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        return FileInputStream(descriptor).use { input ->
            val facts = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(facts.st_mode) && facts.st_uid == Process.myUid() && facts.st_nlink == 1L &&
                (facts.st_mode and 511) in setOf(256, 384) && facts.st_size in 1..maximum.toLong())
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(result.size() <= maximum - count)
                result.write(buffer, 0, count)
            }
            result.toByteArray().also { require(it.isNotEmpty()) }
        }
    }
}
