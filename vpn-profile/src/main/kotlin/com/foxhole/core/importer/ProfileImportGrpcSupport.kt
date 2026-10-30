package com.foxhole.core.importer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal

internal object ProfileImportGrpcSupport {
    private val fields = setOf("type", "service_name", "multi_mode", "authority", "rpc_path", "user_agent", "idle_timeout", "idle_timeout_ms", "health_check_timeout", "ping_timeout", "health_check_timeout_ms", "permit_without_stream", "initial_windows_size")
    private val xrayFields = setOf("serviceName", "authority", "multiMode", "user_agent", "idle_timeout", "health_check_timeout", "permit_without_stream", "initial_windows_size")

    fun fromQuery(query: Map<String, String>): JsonObject {
        fun value(vararg names: String) = names.firstNotNullOfOrNull { query[it] ?: query[it.lowercase()] }
        val service = value("serviceName", "service_name", "path").orEmpty()
        val mode = value("mode")?.lowercase() ?: "gun"
        require(mode in setOf("", "none", "gun", "multi")) { "unsupported grpc mode" }
        val result = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("grpc"), "service_name" to JsonPrimitive(service), "multi_mode" to JsonPrimitive(mode == "multi"))
        value("authority")?.let { result["authority"] = JsonPrimitive(it) }
        value("rpc_path")?.let { result["rpc_path"] = JsonPrimitive(it) }
        value("user_agent", "userAgent")?.let { result["user_agent"] = JsonPrimitive(it) }
        require(value("ping_timeout") == null) { "ping_timeout is a JSON-only grpc duration alias" }
        val rawIdle = value("idle_timeout")
        val rawHealth = value("health_check_timeout")
        val milliseconds = value("idle_timeout_ms") != null || value("health_check_timeout_ms") != null
        require(!milliseconds || rawIdle == null && rawHealth == null) { "conflicting grpc timeout aliases" }
        fun seconds(raw: String?): Long {
            val parsed = raw?.let { it.toIntOrNull() ?: throw IllegalArgumentException("grpc timeout must be integer seconds") } ?: 0
            return parsed.coerceAtLeast(0).toLong()
        }
        val idleSeconds = seconds(rawIdle)
        val healthSeconds = seconds(rawHealth)
        for (key in listOf("idle_timeout_ms", "health_check_timeout_ms")) value(key)?.let {
            result[key] = JsonPrimitive(it.toLongOrNull() ?: throw IllegalArgumentException("invalid grpc milliseconds"))
        }
        value("permit_without_stream")?.let { result["permit_without_stream"] = JsonPrimitive(uriBoolean(it)) }
        value("initial_windows_size")?.let {
            val parsed = it.toIntOrNull() ?: throw IllegalArgumentException("invalid grpc window")
            result["initial_windows_size"] = JsonPrimitive(parsed.coerceAtLeast(0))
        }
        if (!milliseconds && (rawIdle != null || rawHealth != null || result["permit_without_stream"] == JsonPrimitive(true))) {
            val active = idleSeconds > 0 || healthSeconds > 0 || result["permit_without_stream"] == JsonPrimitive(true)
            result["idle_timeout"] = JsonPrimitive("${if (active) idleSeconds.coerceAtLeast(10) else 0}s")
            result["health_check_timeout"] = JsonPrimitive("${if (healthSeconds == 0L) 20 else healthSeconds}s")
        }
        return canonical(JsonObject(result), singBoxPath = false)
    }

    fun fromXray(settings: JsonObject?): JsonObject {
        val source = settings ?: JsonObject(emptyMap())
        require(source.keys.all { it in xrayFields }) { "unsupported grpc setting" }
        val result = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("grpc"), "service_name" to JsonPrimitive(source.stringValue("serviceName") ?: ""))
        result["multi_mode"] = JsonPrimitive(source.booleanValue("multiMode") ?: false)
        source["authority"]?.let { result["authority"] = JsonPrimitive(source.stringValue("authority") ?: throw IllegalArgumentException("invalid grpc authority")) }
        result["user_agent"] = JsonPrimitive(normalizeXrayUserAgent(source.stringValue("user_agent").orEmpty()))
        for (key in listOf("idle_timeout", "health_check_timeout")) source[key]?.let {
            val seconds = (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.content?.toLongOrNull() ?: throw IllegalArgumentException("invalid grpc timeout")
            val effective = if (key == "health_check_timeout" && seconds <= 0) 20L else seconds.coerceAtLeast(0)
            require(effective <= 86400) { "grpc timeout exceeds limit" }
            result[key] = JsonPrimitive("${effective}s")
        }
        source["permit_without_stream"]?.let { result["permit_without_stream"] = JsonPrimitive(source.booleanValue("permit_without_stream") ?: throw IllegalArgumentException("invalid grpc boolean")) }
        source["initial_windows_size"]?.let {
            val raw = (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.content?.toLongOrNull() ?: throw IllegalArgumentException("invalid grpc window")
            result["initial_windows_size"] = JsonPrimitive(raw.coerceAtLeast(0))
        }
        val idle = result["idle_timeout"]?.let { durationMillis(it) } ?: 0
        val healthExplicit = (source["health_check_timeout"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { it > 0 } ?: false
        if (idle in 1..9999L || idle == 0L && (healthExplicit || source.booleanValue("permit_without_stream") == true)) result["idle_timeout"] = JsonPrimitive("10s")
        return canonical(JsonObject(result), singBoxPath = false)
    }

    fun canonical(source: JsonObject, singBoxPath: Boolean): JsonObject {
        require(source.keys.all { it in fields }) { "unsupported grpc setting" }
        val service = source.stringValue("service_name") ?: ""
        require(service.toByteArray(Charsets.UTF_8).size <= 2048 && service.none { it.isISOControl() }) { "invalid grpc service" }
        val multi = source.booleanValue("multi_mode") ?: false
        val result = source.toMutableMap()
        result["type"] = JsonPrimitive("grpc")
        result["service_name"] = JsonPrimitive(service)
        result["multi_mode"] = JsonPrimitive(multi)
        source.stringValue("authority")?.let {
            require(it.length <= 1024 && it.all { ch -> ch.code in 0x21..0x7e } && it.none { ch -> ch in "/?#@" }) { "invalid grpc authority" }
            if (it.isEmpty()) result.remove("authority")
        }
        source.stringValue("user_agent")?.let { require(it.toByteArray(Charsets.UTF_8).size <= 1024 && it.none { ch -> ch.code < 0x20 || ch.code == 0x7f }) { "invalid grpc user agent" } }
        source["permit_without_stream"]?.let { source.booleanValue("permit_without_stream") ?: throw IllegalArgumentException("invalid grpc boolean") }
        source["initial_windows_size"]?.let { element ->
            val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw IllegalArgumentException("invalid grpc window")
            require(value in 0..2147483647L) { "invalid grpc window" }
        }
        for (key in listOf("idle_timeout", "health_check_timeout", "ping_timeout")) source[key]?.let { durationMillis(it) }
        for (key in listOf("idle_timeout_ms", "health_check_timeout_ms")) source[key]?.let {
            val value = (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.content?.toLongOrNull() ?: throw IllegalArgumentException("invalid grpc milliseconds")
            require(value in 0..86400000L) { "invalid grpc milliseconds" }
            val aliases = if (key == "idle_timeout_ms") setOf("idle_timeout") else setOf("health_check_timeout", "ping_timeout")
            require(aliases.none { alias -> source.containsKey(alias) }) { "conflicting grpc timeout aliases" }
        }
        if (source.containsKey("health_check_timeout") && source.containsKey("ping_timeout")) require(durationMillis(source.getValue("health_check_timeout")) == durationMillis(source.getValue("ping_timeout"))) { "conflicting grpc timeout aliases" }
        val override = source.stringValue("rpc_path")
        if (override != null) validateRpcPath(override)
        if (singBoxPath && override == null && !source.containsKey("multi_mode")) {
            val actual = "/${escapeServiceSegment(service)}/Tun"
            validateRpcPath(actual)
            result["rpc_path"] = JsonPrimitive(actual)
        }
        return JsonObject(result)
    }

    private fun normalizeXrayUserAgent(value: String): String {
        // Coherent source-adapter policy; canonical and Mihomo values stay raw.
        val chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        return when (value.lowercase()) {
            "", "chrome" -> chrome
            "edge" -> "$chrome Edg/151.0.0.0"
            "firefox" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            "golang" -> ""
            else -> value
        }
    }

    private fun escapeServiceSegment(value: String): String = buildString {
        // Go net/url.PathEscape for one segment, not form/query encoding.
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val number = byte.toInt() and 255
            val char = number.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.~$&+:=@") append(char)
            else {
                append('%')
                append("0123456789ABCDEF"[number ushr 4])
                append("0123456789ABCDEF"[number and 15])
            }
        }
    }

    fun durationMillis(element: JsonElement): Long {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("invalid grpc duration")
        require(text.length <= 128) { "grpc duration exceeds limit" }
        if (text == "0") return 0L
        val pattern = Regex("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s|m|h)")
        var offset = 0
        var millis = BigDecimal.ZERO
        for (match in pattern.findAll(text)) {
            require(match.range.first == offset) { "invalid grpc duration" }
            val factor = when (match.groupValues[2]) {
                "ns" -> "0.000001"
                "us", "µs" -> "0.001"
                "ms" -> "1"
                "s" -> "1000"
                "m" -> "60000"
                else -> "3600000"
            }
            millis = millis.add(BigDecimal(match.groupValues[1]).multiply(BigDecimal(factor)))
            offset = match.range.last + 1
        }
        require(offset > 0 && offset == text.length) { "invalid grpc duration" }
        val value = try { millis.longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("grpc duration precision is unsupported") }
        require(value in 0..86400000L) { "grpc duration exceeds limit" }
        return value
    }

    private fun validateRpcPath(value: String) {
        // RPC overrides are literal HTTP/2 origin-form paths. Percent is not
        // decoded or re-escaped; only actual header/path delimiters are refused.
        require(value.startsWith('/') && value.toByteArray(Charsets.UTF_8).size <= 8192 && value.none { it.isISOControl() || it == ' ' || it == '?' || it == '#' }) { "invalid grpc RPC path" }
    }

    private fun JsonObject.stringValue(key: String): String? = this[key]?.let {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: throw IllegalArgumentException("invalid grpc string")
    }

    private fun JsonObject.booleanValue(key: String): Boolean? = this[key]?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull ?: throw IllegalArgumentException("invalid grpc boolean")
    }

    private fun uriBoolean(value: String): Boolean = when (value.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> throw IllegalArgumentException("invalid grpc boolean")
    }
}
