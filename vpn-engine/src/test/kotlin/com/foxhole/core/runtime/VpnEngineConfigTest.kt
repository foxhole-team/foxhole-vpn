package com.foxhole.core.runtime

import com.foxhole.vpn.profile.VpnProfileImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEngineConfigTest {
    private val profile = VpnProfileImporter().import(
        "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Example",
    ).profiles.single()

    @Test
    fun `VPN config has no local guard listeners overlays or package firewall`() {
        val config = VpnEngineConfig.prepare(profile)
        val engine = Json.parseToJsonElement(config.engineJson).jsonObject
        val runtime = engine.getValue("runtime").jsonObject
        assertFalse("control_proxy" in runtime)
        assertFalse("loopback_inbounds" in runtime)
        assertFalse("local_guard" in runtime)
        assertFalse("outbounds" in engine)
        assertEquals("vpn", engine.getValue("traffic").jsonObject.getValue("default_action").jsonPrimitive.content)
        assertTrue(config.tun.allowedApplications.isEmpty())
        assertTrue(config.tun.disallowedApplications.isEmpty())
        assertEquals("primary", engine.getValue("dns").jsonObject.getValue("route").jsonPrimitive.content)
    }

    @Test
    fun `IPv6 enabled tunnel cannot retain an IPv6 bypass route`() {
        val root = Json.parseToJsonElement(profile.normalizedConfigJson).jsonObject
        val tun = root.getValue("inbounds").let { (it as JsonArray).single().jsonObject }
        val changedTun = JsonObject(tun + mapOf(
            "address" to Json.parseToJsonElement("[\"172.19.0.1/30\",\"fdfe:dcba:9876::1/126\"]"),
            "route_address" to Json.parseToJsonElement("[\"0.0.0.0/0\",\"2001:db8::/32\"]"),
        ))
        val changed = profile.copy(normalizedConfigJson = JsonObject(root + ("inbounds" to JsonArray(listOf(changedTun)))).toString())
        assertThrows(IllegalArgumentException::class.java) { VpnEngineConfig.prepare(changed) }
    }

    @Test
    fun `imported direct bypass policy cannot silently become a VPN-only route`() {
        val root = Json.parseToJsonElement(profile.normalizedConfigJson).jsonObject
        val route = JsonObject(root.getValue("route").jsonObject + ("rules" to JsonArray(listOf(buildJsonObject {
            put("outbound", "direct")
            put("package_name", "com.example.app")
        }))))
        val changed = profile.copy(normalizedConfigJson = JsonObject(root + ("route" to route)).toString())
        assertThrows(IllegalStateException::class.java) { VpnEngineConfig.prepare(changed) }
    }
}
