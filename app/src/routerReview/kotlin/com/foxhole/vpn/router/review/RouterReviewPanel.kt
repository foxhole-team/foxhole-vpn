package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterInstallProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Synthetic state of one router. No release verification or real route proof is represented. */
internal class RouterReviewPanel(initiallyInstalled: Boolean = true) : AutoCloseable {
    private var pin = "1234".toCharArray()
    private var changePinRequired = true
    private var rootToken = ROOT_TOKEN
    @Volatile var installed = initiallyInstalled
        private set
    private var revision = 1L
    private var selectedServer: String? = null
    private var protocols = setOf("hysteria2", "vless", "wireguard")
    private val updatePanel = RouterReviewUpdatePanel { protocols }
    private val enginePanel = RouterReviewEnginePanel({ protocols }, { protocols = it }, { profiles[selectedServer]?.text("protocol") })
    var incomingConfigured = true
    private val pairs = linkedMapOf<String, JsonObject>()
    private val sessions = linkedMapOf<String, String>()
    private val profiles = linkedMapOf<String, JsonObject>()
    private val clients = linkedMapOf<String, JsonObject>()
    private var settings = buildJsonObject {
        put("localRules", true)
        put("localScope", "all")
        put("killSwitch", true)
    }
    private var countries = JsonArray(emptyList())
    private var devices = JsonArray(listOf(
        device("device-000000000001", "Demo laptop", "192.168.1.50"),
        device("device-000000000003", "Demo tablet", "192.168.1.51", online = false),
    ))
    private var sites = JsonArray(emptyList())
    private val phoneDevices = mutableMapOf<String, String>()
    @Volatile var installationCount = 0
        private set

    init {
        profiles["server-000000000001"] = profile(
            "server-000000000001",
            "Demo Hysteria2",
            DEMO_PROFILE_URI,
            "hysteria2",
        )
    }

    @Synchronized
    fun install(selected: Set<RouterInstallProtocol>) {
        installed = true
        protocols = selected.map { it.canonical }.toSet()
        pin.fill('\u0000')
        pin = "1234".toCharArray()
        changePinRequired = true
        rootToken = ROOT_TOKEN
        pairs.clear()
        sessions.clear()
        phoneDevices.clear()
        clients.clear()
        selectedServer = null
        profiles.entries.removeAll { it.value.text("protocol") !in protocols }
        installationCount++
        revision++
    }

    @Synchronized
    fun reply(action: String, payload: JsonObject): JsonObject {
        if (action == "package.status") return buildJsonObject {
            put("schema", 1); put("product", "foxhole"); put("installed", installed)
            put("pinConfigured", !changePinRequired); put("resetSupported", true)
        }
        if (action == "unlock") return unlock(payload)
        if (action == "pair.unlock") return unlockPair(payload)
        val token = payload.text("token")
        if (token == rootToken) return rootAction(action, payload)
        val pairId = sessions[token] ?: return error("unauthorized")
        if (pairId !in pairs) return error("unauthorized")
        return pairedAction(action, payload, pairId)
    }

    private fun unlock(payload: JsonObject): JsonObject {
        if (payload.text("pin") != pin.concatToString()) return error("invalid_pin")
        return buildJsonObject {
            put("token", rootToken)
            put("pinChangeRequired", changePinRequired)
        }
    }

    private fun unlockPair(payload: JsonObject): JsonObject {
        val id = payload.text("id")
        val pair = pairs[id] ?: return error("unauthorized")
        if (pair.text("credential") != payload.text("credential")) return error("unauthorized")
        val token = "p1.${reviewDigest("offline-demo-session-$id")}"
        sessions[token] = id
        return buildJsonObject { put("token", token) }
    }

    private fun rootAction(action: String, payload: JsonObject): JsonObject = when (action) {
        "state" -> if (changePinRequired) error("pin_required") else state()
        "setup.reset" -> resetSetup(payload)
        "pin.change" -> changePin(payload)
        "pair.create" -> {
            if (changePinRequired) {
                error("pin_required")
            } else {
                val id = payload.text("id")
                pairs[id] = JsonObject(payload - "token")
                buildJsonObject { put("id", id) }
            }
        }
        "lock" -> JsonObject(emptyMap())
        else -> error("unauthorized")
    }

    private fun changePin(payload: JsonObject): JsonObject {
        val next = payload.text("newPin")
        if (!next.matches(Regex("[0-9]{4}"))) return error("invalid_pin")
        if (next == pin.concatToString()) return error("pin_unchanged")
        pin.fill('\u0000')
        pin = next.toCharArray()
        changePinRequired = false
        return buildJsonObject { put("token", rootToken) }
    }

    private fun resetSetup(payload: JsonObject): JsonObject {
        if (changePinRequired || payload["confirm"] != JsonPrimitive(true)) return error("unauthorized")
        if (!revisionMatches(payload)) return error("revision_conflict")
        pairs.clear(); sessions.clear(); profiles.clear(); clients.clear(); phoneDevices.clear()
        devices = JsonArray(emptyList()); countries = JsonArray(emptyList()); sites = JsonArray(emptyList())
        selectedServer = null
        settings = buildJsonObject { put("localRules", true); put("localScope", "all"); put("killSwitch", true) }
        changePinRequired = true
        pin.fill('\u0000')
        revision++
        rootToken = reviewDigest("offline-demo-restricted-reset-$revision")
        return buildJsonObject {
            put("schema", 1)
            put("token", rootToken); put("pinChangeRequired", true); put("reset", true)
            put("scope", "foxhole"); put("revision", revision); put("simulation", true)
        }
    }

    private fun pairedAction(action: String, payload: JsonObject, pairId: String): JsonObject = when (action) {
        "updates.status", "updates.check", "updates.plan", "updates.apply", "updates.job", "updates.settings" -> updatePanel.reply(action, payload, pairId)
        "engines.status", "engines.plan", "engines.apply", "engines.job" -> enginePanel.reply(action, payload, pairId)
        "state" -> state()
        "telemetry" -> buildJsonObject {
            put("sample", buildJsonObject {
                put("wan", buildJsonObject { put("country", "NL") })
                put("vpn", buildJsonObject { put("country", "JP") })
            })
        }
        "phone.capabilities" -> capabilities()
        "server.test" -> testProfile(payload)
        "server.save" -> saveProfile(payload)
        "server.get" -> profiles[payload.text("id")] ?: error("unsupported_config")
        "server.start" -> start(payload)
        "server.stop" -> stop(payload)
        "server.delete" -> deleteProfile(payload)
        "settings" -> updateSettings(payload)
        "phone.provision" -> provisionPhone(payload, pairId)
        "phone.status" -> phoneStatus(payload, pairId)
        "client.get" -> exportClient(payload)
        "client.save" -> createClient(payload)
        "client.delete" -> deleteClient(payload)
        "client.status" -> buildJsonObject {
            put("schema", 1); put("revision", revision)
            put("clients", JsonArray(clients.keys.mapIndexed { index, id -> buildJsonObject {
                put("id", id); put("online", index % 2 == 0)
            } }))
            put("simulation", true)
        }
        "device.list" -> buildJsonObject { put("devices", devices) }
        "package.remove" -> removePackage(payload, pairId)
        "pair.revoke" -> revokePair(payload, pairId)
        else -> error("unsupported_config")
    }

    private fun state(extra: Map<String, JsonElement> = emptyMap()): JsonObject = buildJsonObject {
        put("schema", 1)
        put("revision", revision)
        put("settings", settings)
        put("countries", countries)
        put("devices", devices)
        put("sites", sites)
        put("servers", JsonArray(profiles.values.toList()))
        put("clients", JsonArray(clients.values.toList()))
        put("packageRemovalAvailable", true)
        put(
            "runtime",
            buildJsonObject {
                put("desiredServerId", selectedServer?.let(::JsonPrimitive) ?: JsonNull)
                put("connected", selectedServer != null)
                put("simulation", true)
            },
        )
        extra.forEach { (key, value) -> put(key, value) }
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("coreProtocols", JsonArray(protocols.map(::JsonPrimitive)))
        put("legacyProtocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
        put("inboundProtocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
        put("phoneRemoteProvisioning", true)
        put("subscriptionSelection", true)
        put("independentPhoneChoices", true)
        put("phoneRegistration", true)
        put("homeProof", false)
        put("phoneHandoff", false)
        put("providerResolve", false)
        put("profileSourceImport", true)
        put("packageUpdates", true)
        put("simulation", true)
    }

    private fun testProfile(payload: JsonObject): JsonObject = buildJsonObject {
        put("valid", true)
        put("scope", "config")
        put("code", "validated")
        put("protocol", demoProtocol(payload.text("config")))
        put("endpoint", "1.1.1.1:443")
        put("configSha256", reviewDigest(payload.text("config").trim()))
        put("simulation", true)
    }

    private fun saveProfile(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        val requested = payload.text("id")
        val id = requested.ifEmpty { "server-${(profiles.size + revision + 1).toString(16).padStart(12, '0')}" }
        val config = payload.text("config")
        val protocol = config.substringBefore("://").lowercase().takeIf { it in protocols } ?: protocols.first()
        profiles[id] = JsonObject(
            profile(id, payload.text("name"), config, protocol) + mapOf(
                "engine" to JsonPrimitive(payload.text("engine")),
                "subscription" to (payload["subscription"] ?: JsonNull),
                "portableProfile" to buildJsonObject {
                    put("content", config)
                    put("sha256", reviewDigest(config))
                    put("configSha256", reviewDigest(config.trim()))
                },
            ),
        )
        revision++
        return state()
    }

    private fun start(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        val id = payload.text("id")
        if (id !in profiles) return error("unsupported_config")
        selectedServer = id
        revision++
        return state()
    }

    private fun stop(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        selectedServer = null
        revision++
        return state()
    }

    private fun deleteProfile(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        val id = payload.text("id")
        if (profiles.remove(id) == null) return error("unsupported_config")
        if (selectedServer == id) selectedServer = null
        revision++
        return state()
    }

    private fun updateSettings(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        settings = payload["settings"] as? JsonObject ?: return error("invalid_config")
        countries = payload["countries"] as? JsonArray ?: return error("invalid_config")
        devices = payload["devices"] as? JsonArray ?: return error("invalid_config")
        sites = payload["sites"] as? JsonArray ?: return error("invalid_config")
        revision++
        return state()
    }

    private fun provisionPhone(payload: JsonObject, pairId: String): JsonObject {
        val register = payload.flag("registerPhone", true)
        val remote = payload.flag("createRemote")
        val lanId = if (register) "device-000000000002" else null
        if (lanId != null) {
            phoneDevices[pairId] = lanId
            devices = JsonArray(
                devices.filter { (it as? JsonObject)?.text("id") != lanId } +
                    device(lanId, payload.text("name"), payload.text("address")),
            )
        }
        val clientId = if (remote) "client-${(revision + 1).toString(16).padStart(12, '0')}" else null
        if (clientId != null) {
            incomingConfigured = true
            clients[clientId] = buildJsonObject {
                put("id", clientId)
                put("name", "Demo ${payload.text("name")}")
                put("protocol", "hysteria2")
                put("provisioned", true)
                put("inactive", false)
            }
        }
        revision++
        return buildJsonObject {
            put("revision", revision)
            put("lanDeviceId", lanId?.let(::JsonPrimitive) ?: JsonNull)
            put("remoteClientId", clientId?.let(::JsonPrimitive) ?: JsonNull)
            put("profileUri", if (remote) JsonPrimitive(DEMO_PROFILE_URI) else JsonNull)
            put("remoteReady", false)
        }
    }

    private fun phoneStatus(payload: JsonObject, pairId: String): JsonObject = buildJsonObject {
        put("schema", 1)
        put("pairingId", pairId)
        put("address", payload.text("address"))
        put("lanDeviceId", phoneDevices[pairId]?.let(::JsonPrimitive) ?: JsonNull)
        put("serverId", selectedServer?.let(::JsonPrimitive) ?: JsonNull)
        put("generation", JsonNull)
        put("checkedAgeMs", JsonNull)
        put("validated", false)
        put("vpnBound", false)
        put("routed", false)
        put("failClosed", false)
        put("simulation", true)
    }

    private fun exportClient(payload: JsonObject): JsonObject {
        val id = payload.text("id")
        val client = clients[id] ?: return error("unsupported_config")
        return buildJsonObject {
            put(
                "export",
                JsonObject(
                    client + mapOf(
                        "uri" to JsonPrimitive(DEMO_PROFILE_URI),
                        "config" to JsonPrimitive(DEMO_PROFILE_URI),
                    ),
                ),
            )
        }
    }

    private fun createClient(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        val name = payload.text("name").trim()
        if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > 48 || name.any(Char::isISOControl) ||
            payload.text("deviceType") != "smartphone") return error("invalid_config")
        val id = "client-${(revision + 1).toString(16).padStart(12, '0')}"
        clients[id] = buildJsonObject {
            put("id", id); put("name", name); put("deviceType", "smartphone")
            put("protocol", "hysteria2"); put("provisioned", incomingConfigured); put("inactive", false)
        }
        revision++
        val export = exportClient(buildJsonObject { put("id", id) })["export"] as JsonObject
        return buildJsonObject { put("state", state()); put("client", export) }
    }

    private fun deleteClient(payload: JsonObject): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        val id = payload.text("id")
        val removed = clients.remove(id) ?: return error("unsupported_config")
        revision++
        return state(
            mapOf(
                "clientRevocation" to buildJsonObject {
                    put("id", id)
                    put("applied", true)
                    put("targetRevoked", removed.flag("provisioned"))
                },
            ),
        )
    }

    private fun revokePair(payload: JsonObject, pairId: String): JsonObject {
        if (payload.text("id") != pairId) return error("unauthorized")
        pairs.remove(pairId)
        sessions.entries.removeAll { it.value == pairId }
        return JsonObject(emptyMap())
    }

    private fun removePackage(payload: JsonObject, pairId: String): JsonObject {
        if (!revisionMatches(payload)) return error("revision_conflict")
        if (payload.text("pairingId") != pairId) return error("unauthorized")
        installed = false
        profiles.clear()
        clients.clear()
        pairs.clear()
        sessions.clear()
        phoneDevices.clear()
        selectedServer = null
        revision++
        return buildJsonObject {
            put("pairingId", pairId)
            put("revision", revision)
            put("packageRemoved", true)
            put("ownedSettingsRestored", true)
            put("simulation", true)
        }
    }

    private fun revisionMatches(payload: JsonObject): Boolean =
        (payload["revision"] as? JsonPrimitive)?.longOrNull == revision

    @Synchronized
    override fun close() {
        pin.fill('\u0000')
        pairs.clear()
        sessions.clear()
        profiles.clear()
        clients.clear()
        phoneDevices.clear()
    }

    private companion object {
        val ROOT_TOKEN = reviewDigest("offline-demo-root-session")

        fun error(code: String): JsonObject = buildJsonObject { put("_foxhole_error", code) }

        fun demoProtocol(config: String): String = config.substringBefore("://").lowercase()
            .takeIf { it in setOf("hysteria2", "hy2", "vless", "wireguard") }
            ?.let { if (it == "hy2") "hysteria2" else it } ?: "hysteria2"

        fun profile(id: String, name: String, config: String, protocol: String): JsonObject = buildJsonObject {
            put("id", id)
            put("name", name)
            put("protocol", protocol)
            put("endpoint", "1.1.1.1:443")
            put("config", config)
            put("configSha256", reviewDigest(config.trim()))
            put("engine", "")
            put("subscription", JsonNull)
        }

        fun device(id: String, name: String, address: String, online: Boolean = true): JsonObject = buildJsonObject {
            put("id", id)
            put("name", name)
            put("address", address)
            put("mac", "02:00:00:00:00:${id.takeLast(2)}")
            put("bindMac", true)
            put("route", "vpn")
            put("online", online)
        }

        fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        fun JsonObject.flag(key: String, default: Boolean = false): Boolean =
            (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    }
}
