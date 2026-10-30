package com.foxhole.vpn.router

import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RouterLanAcceptanceAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val manager get() = requireNotNull(context.getSystemService(ConnectivityManager::class.java))

    @Test fun deniedApi37LocalNetworkPermissionStopsBeforeWifiSelectionOrAnyProbe() {
        assumeTrue(Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37)
        assumeTrue(arguments.getString("routerPermissionState") == "denied")
        assertFalse(routerLocalNetworkAccessGranted(context))
        try {
            RouterWifiConnection.select(manager, RouterEndpoint("192.168.1.1"), accessCheck = { requireRouterLocalNetworkAccess(context) })
            error("denied local network access accepted")
        } catch (_: RouterLocalNetworkPermissionRequired) { }
    }

    @Test fun socketUsesTheActualNonVpnWifiAddressOfTheIsolatedRouterLan() {
        val endpoint = endpoint("routerSshPort", 22)
        val wifi = selected(endpoint)
        val capabilities = requireNotNull(manager.getNetworkCapabilities(wifi.network))
        assertTrue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        wifi.createSocket().use { socket ->
            socket.connect(endpoint.socketAddress(), 5000)
            assertEquals(wifi.localAddress, socket.localAddress)
            socket.soTimeout = 5000
            val prefix = ByteArray(4)
            var count = 0
            while (count < prefix.size) {
                val read = socket.getInputStream().read(prefix, count, prefix.size - count)
                require(read > 0)
                count += read
            }
            assertEquals("SSH-", prefix.toString(Charsets.US_ASCII))
        }
        wifi.requireCurrent()
    }

    @Test fun unknownSshIdentityIsObservedBeforeAnyPasswordIsSupplied() {
        val endpoint = endpoint("routerSshPort", 22)
        val expected = publicArgument("routerSshKeySha256", Regex("[a-f0-9]{64}"))
        RouterSshConnection(endpoint.socketAddress(), "android-owned-lab", null, selected(endpoint)).use { client ->
            try { client.connect(null); error("unreviewed SSH identity accepted") }
            catch (review: RouterSshTrustRequired) { assertEquals(expected, digest(review.key)) }
        }
    }

    @Test fun approvedSshIdentityAuthenticatesTheCustomRootPasswordAndReadsOnlyBoardFacts() {
        val endpoint = endpoint("routerSshPort", 22)
        val key = observedSshKey(endpoint)
        val password = labSecret("rootPassword")
        try {
            RouterSshConnection(endpoint.socketAddress(), "android-owned-lab", key, selected(endpoint)).use { client ->
                client.connect(password)
                assertTrue(password.all { it == '\u0000' })
                val board = client.read(RouterReadCommand.BOARD)
                val document = Json.parseToJsonElement(board.toString(Charsets.UTF_8)) as JsonObject
                val release = document["release"] as JsonObject
                assertEquals("OpenWrt", release.text("distribution"))
                assertEquals(publicArgument("routerOpenWrtVersion", Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), release.text("version"))
            }
        } finally { password.fill('\u0000'); key.fill(0) }
    }

    @Test fun changedSshIdentityIsRefusedAndThePasswordBufferIsCleared() {
        val endpoint = endpoint("routerSshPort", 22)
        val key = observedSshKey(endpoint).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val password = labSecret("rootPassword")
        try {
            RouterSshConnection(endpoint.socketAddress(), "android-owned-lab", key, selected(endpoint)).use { client ->
                try { client.connect(password); error("changed SSH identity accepted") }
                catch (_: RouterIdentityChanged) { assertTrue(password.all { it == '\u0000' }) }
            }
        } finally { password.fill('\u0000'); key.fill(0) }
    }

    @Test fun tlsIdentityReviewCarriesNoPinAndAMismatchedLeafCannotUnlock() = runBlocking {
        val endpoint = endpoint("routerTlsPort", 8443)
        selected(endpoint)
        val transport = WifiRouterWizardTransport(manager, accessCheck = { requireRouterLocalNetworkAccess(context) })
        val identity = transport.inspectIdentity(endpoint)
        assertEquals(publicArgument("routerCertificateSha256", Regex("[a-f0-9]{64}")), identity.certificateSha256)
        val wrong = identity.copy(certificateSha256 = (if (identity.certificateSha256[0] == 'a') "b" else "a") + identity.certificateSha256.drop(1))
        val pin = labSecret("panelPin")
        try {
            transport.api(wrong).use { api ->
                try { api.unlock(pin); error("wrong management leaf accepted") }
                catch (failure: RouterApiFailure) { assertEquals(RouterApiError.IDENTITY, failure.kind) }
            }
            assertTrue(pin.all { it == '\u0000' })
        } finally { pin.fill('\u0000') }
    }

    @Test fun isolatedLabPairingReopensWithAnIndependentKeyAndRevocationIsReadBack() = runBlocking {
        val endpoint = endpoint("routerTlsPort", 8443)
        selected(endpoint)
        val identity = RouterTlsIdentity(endpoint, publicArgument("routerCertificateSha256", Regex("[a-f0-9]{64}")))
        val transport = WifiRouterWizardTransport(manager, accessCheck = { requireRouterLocalNetworkAccess(context) })
        val pin = labSecret("panelPin")
        val pairing = RouterPairing.create(identity, "Android owned lab acceptance")
        try {
            transport.api(identity).use { api ->
                api.unlock(pin).use { administrative ->
                    assertFalse(administrative.changePinRequired)
                    var created = false
                    try {
                        api.createPairing(administrative, pairing)
                        created = true
                        api.unlockPairing(pairing).use { independent ->
                            assertTrue(independent.token().startsWith("p1."))
                            val provisioner = RouterHttpProvisioner(localAddress = { selected(it.endpoint).localAddress.hostAddress!! })
                            provisioner.capabilities(api, independent)
                            val status = provisioner.phoneStatus(api, independent, pairing.id)
                            assertEquals(pairing.id, status.pairingId)
                            assertEquals(selected(endpoint).localAddress.hostAddress, status.address)
                            assertTrue(status.handoffKnown)
                            assertEquals(null, status.handoff)
                        }
                    } finally { if (created) api.revoke(administrative, pairing) }
                    try { api.unlockPairing(pairing).close(); error("revoked key accepted") }
                    catch (failure: RouterApiFailure) { assertEquals(RouterApiError.PIN, failure.kind) }
                }
            }
        } finally { pin.fill('\u0000'); pairing.clear() }
    }

    private fun endpoint(portName: String, defaultPort: Int): RouterEndpoint {
        assumeTrue("Owned isolated router fixture required", arguments.getString("routerOwnedLab") == "home-lab-20261011")
        val address = publicArgument("routerLanAddress", Regex("[0-9.]+"))
        return RouterEndpoint(address, arguments.getString(portName)?.toInt() ?: defaultPort)
    }

    private fun selected(endpoint: RouterEndpoint): RouterWifiConnection {
        requireRouterLocalNetworkAccess(context)
        assumeTrue("Stop any VPN before these transport-only checks", manager.allNetworks.none {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        })
        return RouterWifiConnection.select(manager, endpoint, accessCheck = { requireRouterLocalNetworkAccess(context) }).also { it.requireCurrent() }
    }

    private fun observedSshKey(endpoint: RouterEndpoint): ByteArray {
        val expected = publicArgument("routerSshKeySha256", Regex("[a-f0-9]{64}"))
        RouterSshConnection(endpoint.socketAddress(), "android-owned-lab", null, selected(endpoint)).use { client ->
            try { client.connect(null) } catch (review: RouterSshTrustRequired) {
                assertEquals(expected, digest(review.key))
                return review.key
            }
        }
        error("SSH identity review missing")
    }

    private fun labSecret(field: String): CharArray {
        assumeTrue("Private synthetic lab inputs require a debuggable target", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val file = File(context.noBackupFilesDir, "router-lab-acceptance/auth.json")
        require(file.isFile && file.length() in 1..4096) { "Private owned lab inputs missing" }
        val bytes = file.readBytes()
        return try {
            val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            requireNotNull(document.text(field)).toCharArray()
        } catch (_: Exception) { throw AssertionError("Private owned lab input shape invalid") }
        finally { bytes.fill(0) }
    }

    private fun publicArgument(key: String, shape: Regex): String = requireNotNull(arguments.getString(key)).also {
        require(it.matches(shape)) { "Public fixture identity invalid" }
    }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
