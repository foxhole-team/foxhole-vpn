package com.foxhole.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.importer.exportPortableConfigs
import com.foxhole.core.network.ensurePublicSubscriptionUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileFormatsAndroidTest {
    private val parser = ProfileImportParser(Json { ignoreUnknownKeys = false })

    @Test fun clashEventsAndOptionalApisWorkOnTheSupportedAndroidRuntime() {
        val body = "proxies:\n - {name: SS, type: ss, server: 1.1.1.1, port: 8388, cipher: aes-256-gcm, password: 00123, udp: false}"
        val parsed = parser.parseUserInput(body)
        val root = Json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val node = root["outbounds"]!!.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "shadowsocks" }.jsonObject
        assertEquals("00123", node["password"]!!.jsonPrimitive.content)
        assertEquals("tcp", node["network"]!!.jsonPrimitive.content)
        val copy = exportPortableConfigs("SS", listOf(root.toString()))
        assertTrue(copy.contains("udp=false"))
        assertEquals(1, parser.parseUserInput(copy).nodesCount)
    }

    @Test fun unsafeYamlAndUnconsentedTlsAreRefusedOnDevice() {
        for (body in listOf("proxies: &nodes [*nodes]", "proxies: !!unsafe []",
            "proxies:\n - {type: trojan, server: 1.1.1.1, port: 443, password: synthetic, skip-cert-verify: true}")) {
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(body) }
        }
    }

    @Test fun outlineSourceKeepsItsFragmentWithoutAllowingPrivateDownloads() {
        assertEquals("https://keys.example.com/access#Home", "ssconf://keys.example.com/access#Home".ensurePublicSubscriptionUrl().toString())
        assertThrows(IllegalArgumentException::class.java) { "ssconf://127.0.0.1/access".ensurePublicSubscriptionUrl() }
    }
}
