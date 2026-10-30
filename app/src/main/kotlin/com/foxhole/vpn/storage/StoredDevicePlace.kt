package com.foxhole.vpn.storage

import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnLocation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.util.Locale

@Serializable(with = StoredDevicePlaceSerializer::class)
internal data class StoredDevicePlace(
    val countryCode: String = "",
    val city: String = "",
    val country: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun restore(): VpnDeviceLocation? {
        val place = storedDevicePlace(VpnLocation(countryCode, city, country, "", latitude, longitude)) ?: return null
        return VpnDeviceLocation(
            VpnLocation(place.countryCode, place.city, place.country, "", place.latitude, place.longitude),
            DeviceLocationStatus.CACHED,
        )
    }
}

internal fun storedDevicePlace(location: VpnLocation): StoredDevicePlace? {
    val code = location.countryCode.trim().uppercase(Locale.ROOT)
    val city = location.city.trim()
    val country = location.country.trim()
    if (code.length != 2 || code.any { it !in 'A'..'Z' } || city.length > 256 || country.length > 256 ||
        (city.isBlank() && country.isBlank())) return null
    val positioned = validDeviceCoordinates(location.latitude, location.longitude)
    return StoredDevicePlace(code, city, country,
        location.latitude.takeIf { positioned }, location.longitude.takeIf { positioned })
}

private fun validDeviceCoordinates(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

internal object StoredDevicePlaceSerializer : KSerializer<StoredDevicePlace> {
    override val descriptor = buildClassSerialDescriptor("com.foxhole.vpn.storage.StoredDevicePlace") {
        element<String>("countryCode")
        element<String>("city")
        element<String>("country")
        element<Double?>("latitude", isOptional = true)
        element<Double?>("longitude", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: StoredDevicePlace) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("device_place_format")
        json.encodeJsonElement(buildJsonObject {
            put("countryCode", value.countryCode)
            put("city", value.city)
            put("country", value.country)
            if (validDeviceCoordinates(value.latitude, value.longitude)) {
                put("latitude", value.latitude)
                put("longitude", value.longitude)
            }
        })
    }

    override fun deserialize(decoder: Decoder): StoredDevicePlace {
        val json = decoder as? JsonDecoder ?: throw SerializationException("device_place_format")
        val fields = json.decodeJsonElement() as? JsonObject ?: return StoredDevicePlace()
        fun text(name: String) = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun coordinate(name: String) = (fields[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        // Invalid display cache must not discard authenticated profiles.
        return storedDevicePlace(VpnLocation(text("countryCode"), text("city"), text("country"), "",
            coordinate("latitude"), coordinate("longitude"))) ?: StoredDevicePlace()
    }
}
