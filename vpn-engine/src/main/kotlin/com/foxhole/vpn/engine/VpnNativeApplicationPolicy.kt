package com.foxhole.vpn.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Native barrier only. Android owns application capture and DNS namespaces. */
internal class VpnNativeApplicationPolicy(engineJson: String) {
    private val engine = Json.parseToJsonElement(engineJson).jsonObject
    private val dns = engine.getValue("dns")

    fun engine(blocked: Boolean = false): String = JsonObject(engine + ("traffic" to traffic(blocked))).toString()

    fun policy(blocked: Boolean, revision: Long?): String = buildJsonObject {
        if (revision != null) put("expected_revision", revision)
        put("dns", dns)
        put("routes", engine["routes"] ?: JsonArray(emptyList()))
        put("traffic", traffic(blocked))
    }.toString()

    private fun traffic(blocked: Boolean): JsonObject = buildJsonObject {
        put("default_action", "vpn")
        put("tor_enabled", false)
        put("i2p_enabled", false)
        put("quarantine_new_apps", false)
        put("kill_switch", blocked)
    }
}
