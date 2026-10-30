package com.foxhole.vpn.engine

import com.foxhole.core.network.isPrivateOrLocalAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress

internal fun parseNetworkLocation(payload: String): EngineLocation {
    val json = Json.parseToJsonElement(payload).jsonObject
    if (json["success"]?.jsonPrimitive?.booleanOrNull != true) throw EngineException(EngineFailure.GEO)
    fun field(name: String, limit: Int) = json[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        .filterNot(Char::isISOControl).take(limit)
    val ip = field("ip", 64)
    val literal = ip.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) ||
        (':' in ip && ip.matches(Regex("[0-9A-Fa-f:.]+")))
    if (!literal || InetAddress.getByName(ip).isPrivateOrLocalAddress()) throw EngineException(EngineFailure.GEO)
    val countryCode = field("country_code", 2).uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
    fun coordinate(name: String, range: ClosedFloatingPointRange<Double>) =
        (json[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in range }
    val latitude = coordinate("latitude", -90.0..90.0)
    val longitude = coordinate("longitude", -180.0..180.0)
    val coordinatesValid = latitude != null && longitude != null
    return EngineLocation(countryCode, field("city", 128), field("country", 128), ip,
        latitude.takeIf { coordinatesValid }, longitude.takeIf { coordinatesValid })
}
