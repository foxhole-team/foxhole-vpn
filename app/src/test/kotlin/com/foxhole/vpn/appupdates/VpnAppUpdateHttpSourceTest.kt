package com.foxhole.vpn.appupdates

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class VpnAppUpdateHttpSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(2048) { (it % 127).toByte() }
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(payload).hexDigest()
    private val manifest get() = """{"versionCode":19,"versionName":"0.1.2","apkName":"vpn.apk","apkSha256":"$digest"}"""
    private val release get() = """{"tag_name":"v0.1.2","html_url":"https://github.com/foxhole-team/foxhole-vpn/releases/tag/v0.1.2","assets":[{"name":"update-manifest.json","browser_download_url":"$BASE/update-manifest.json","size":256},{"name":"vpn.apk","browser_download_url":"$BASE/vpn.apk","size":${payload.size}}]}"""

    private class OwnedCall(private val value: Request, private val respond: ((Request) -> Response)?) : Call by OkHttpClient().newCall(value) {
        var cancelled = false
        override fun request() = value
        override fun execute(): Response = error("blocking execution is forbidden")
        override fun enqueue(responseCallback: Callback) { respond?.let { responseCallback.onResponse(this, it(value)) } }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
    }

    private fun source(routes: Map<String, Pair<Int, ByteArray>>, requests: MutableList<Request> = mutableListOf(), redirects: Map<String, String> = emptyMap(),
        manifestAssetName: String = VpnAppUpdatePolicy.MANIFEST_ASSET) =
        VpnAppUpdateHttpSource(Call.Factory { request ->
            requests += request
            OwnedCall(request) {
                val (code, bytes) = requireNotNull(routes[it.url.toString()]) { "Unexpected fixture request" }
                Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(code).message("Owned fixture")
                    .apply { redirects[it.url.toString()]?.let { location -> header("Location", location) } }
                    .body(bytes.toResponseBody()).build()
            }
        }, manifestAssetName)

    private fun routes() = mapOf(VpnAppUpdatePolicy.RELEASES_API to (200 to release.toByteArray()),
        "$BASE/update-manifest.json" to (200 to manifest.toByteArray()), "$BASE/vpn.apk" to (200 to payload))

    @Test fun armv7SelectsOnlyItsManifestAndNeverFallsBackToThe64BitChannel() = runTest {
        val requests = mutableListOf<Request>()
        val armName = VpnAppUpdatePolicy.ARMV7_MANIFEST_ASSET
        val armRelease = release.replace("\"assets\":[", "\"assets\":[{\"name\":\"$armName\",\"browser_download_url\":\"$BASE/$armName\",\"size\":256},")
        val armRoutes = routes() + (VpnAppUpdatePolicy.RELEASES_API to (200 to armRelease.toByteArray())) +
            ("$BASE/$armName" to (200 to manifest.toByteArray()))
        assertTrue(source(armRoutes, requests, manifestAssetName = armName).latest(18) is VpnAppUpdateCheck.Available)
        assertTrue(requests.any { it.url.toString() == "$BASE/$armName" })
        assertFalse(requests.any { it.url.toString() == "$BASE/update-manifest.json" })
        requests.clear()
        assertEquals(VpnAppUpdateCheck.Failed(VpnAppUpdateError.METADATA),
            source(routes(), requests, manifestAssetName = armName).latest(18))
        assertEquals(1, requests.size)
        assertThrows(IllegalArgumentException::class.java) { VpnAppUpdateHttpSource(manifestAssetName = "../update-manifest.json") }
    }

    @Test fun beforeFirstRelease404IsQuietButMissingManifestIsAnError() = runTest {
        assertEquals(VpnAppUpdateCheck.NoRelease, source(mapOf(VpnAppUpdatePolicy.RELEASES_API to (404 to byteArrayOf()))).latest(18))
        val missing = source(routes() + ("$BASE/update-manifest.json" to (404 to byteArrayOf()))).latest(18)
        assertEquals(VpnAppUpdateError.METADATA, (missing as VpnAppUpdateCheck.Failed).error)
    }

    @Test fun checkedManifestAndCdnDownloadProduceTheAnnouncedExactBytes() = runTest {
        val requests = mutableListOf<Request>()
        val src = source(routes() + ("$BASE/vpn.apk" to (302 to byteArrayOf())) + (CDN to (200 to payload)), requests, mapOf("$BASE/vpn.apk" to CDN))
        val update = (src.latest(18) as VpnAppUpdateCheck.Available).release
        val file = temporary.newFile()
        val progress = mutableListOf<Pair<Long, Long>>()
        src.download(update, file) { read, total -> progress += read to total }
        assertTrue(payload.contentEquals(file.readBytes()))
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        assertTrue(requests.all { it.header("Authorization") == null })
    }

    @Test fun digestMismatchAndAnnouncedLengthMismatchAreRefused() = runTest {
        for (served in listOf(ByteArray(payload.size), payload.copyOf(payload.size - 1))) {
            val src = source(routes() + ("$BASE/vpn.apk" to (200 to served)))
            val update = (src.latest(18) as VpnAppUpdateCheck.Available).release
            try { src.download(update, temporary.newFile()) { _, _ -> }; error("unverified package accepted") }
            catch (failure: VpnAppUpdateFailure) { assertEquals(VpnAppUpdateError.VERIFICATION, failure.kind) }
        }
    }

    @Test fun redirectedPublicAssetsCannotContactAnotherHostOrPort() = runTest {
        for (target in listOf("http://github.com/a", "https://127.0.0.1/a", "https://evil.example/a", "https://github.com:444/a")) {
            val requests = mutableListOf<Request>()
            val src = source(routes() + ("$BASE/update-manifest.json" to (302 to byteArrayOf())), requests, mapOf("$BASE/update-manifest.json" to target))
            assertEquals(VpnAppUpdateError.METADATA, (src.latest(18) as VpnAppUpdateCheck.Failed).error)
            assertEquals(2, requests.size)
        }
    }

    @Test fun excessiveMetadataAndForeignRepoAreRefusedBeforeApkDownload() = runTest {
        val requests = mutableListOf<Request>()
        val large = source(routes() + ("$BASE/update-manifest.json" to (200 to ByteArray(512 * 1024 + 1))), requests).latest(18)
        assertEquals(VpnAppUpdateError.METADATA, (large as VpnAppUpdateCheck.Failed).error)
        val foreign = release.replace("foxhole-vpn/releases/download", "foxhole-guard/releases/download")
        assertTrue(source(routes() + (VpnAppUpdatePolicy.RELEASES_API to (200 to foreign.toByteArray()))).latest(18) is VpnAppUpdateCheck.Failed)
        assertFalse(requests.any { it.url.toString().endsWith("vpn.apk") })
    }

    @Test fun noDowngradeAndNoRepeatedUpdateForAnInstalledCode() = runTest {
        assertEquals(VpnAppUpdateCheck.UpToDate, source(routes()).latest(19))
        assertEquals(VpnAppUpdateCheck.UpToDate, source(routes()).latest(20))
    }

    @Test fun cancellingTheActualRequestCancelsItsCall() = runTest {
        lateinit var call: OwnedCall
        val src = VpnAppUpdateHttpSource(Call.Factory { OwnedCall(it, null).also { created -> call = created } })
        val job = launch { src.latest(18) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(call.cancelled)
    }

    @Test fun theOperationDeadlineReportsANetworkFailureWithoutCancellingItsOwner() = runTest {
        lateinit var call: OwnedCall
        val src = VpnAppUpdateHttpSource(Call.Factory { OwnedCall(it, null).also { created -> call = created } })
        var result: VpnAppUpdateCheck? = null
        val job = launch { result = src.latest(18) }
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(VpnAppUpdateCheck.Failed(VpnAppUpdateError.NETWORK), result)
        assertFalse(job.isCancelled)
        assertTrue(call.cancelled)
    }

    @Test fun networkDnsRefusesLoopbackButSupportsKnownTunnelDnsAddresses() {
        assertThrows(UnknownHostException::class.java) { appUpdateDns(Dns { listOf(InetAddress.getByName("127.0.0.1")) }).lookup("github.com") }
        assertThrows(UnknownHostException::class.java) { appUpdateDns(Dns { emptyList() }).lookup("github.com") }
        assertEquals("198.18.0.1", appUpdateDns(Dns { listOf(InetAddress.getByName("198.18.0.1")) }).lookup("github.com").single().hostAddress)
    }

    private companion object {
        const val BASE = "https://github.com/foxhole-team/foxhole-vpn/releases/download/v0.1.2"
        const val CDN = "https://release-assets.githubusercontent.com/owned.apk"
    }
}
