package com.foxhole.vpn.engine

import kotlinx.serialization.json.*
import java.security.SecureRandom

/** Session-local authenticated primary-outbound proof, independent of destination exceptions. */
internal class VpnProofProxy(val password: String, val port: Int = 0) {
    fun config(): String = buildJsonObject {
        put("name", NAME)
        put("http_port", 0)
        put("allow_anonymous", false)
        put("username", USER)
        put("password", password)
        put("upstream", "profile")
        put("max_sessions", 2)
    }.toString()

    override fun toString(): String = "VpnProofProxy(port=$port, credentials=<redacted>)"

    companion object {
        const val NAME = "vpn-connection-proof"
        const val USER = "vpn-proof"
        fun create(): VpnProofProxy = VpnProofProxy(ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) })

        fun ready(raw: String, secret: String): VpnProofProxy? {
            if (raw.length > 64 * 1024) return null
            val rows = runCatching { Json.parseToJsonElement(raw).jsonObject["inbounds"]?.jsonArray }.getOrNull() ?: return null
            val row = rows.mapNotNull { it as? JsonObject }.singleOrNull { it["name"]?.jsonPrimitive?.contentOrNull == NAME } ?: return null
            if (row["state"]?.jsonPrimitive?.contentOrNull != "ready" || row["upstream"]?.jsonPrimitive?.contentOrNull != "profile") return null
            val address = row["http_address"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!address.startsWith("127.0.0.1:")) return null
            val port = address.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return VpnProofProxy(secret, port)
        }
    }
}
