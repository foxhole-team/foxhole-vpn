package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterInstallProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Offline review simulation only. No packages, release catalog or real installation are represented. */
internal class RouterReviewEnginePanel(private val installed: () -> Set<String>,
    private val replaceInstalled: (Set<String>) -> Unit, private val activeProtocol: () -> String?) {
    private val available = setOf("hysteria2", "vless", "wireguard")
    private val catalog = hash("synthetic-review-catalog")
    private val router = "router-offline-engine-review"
    private var revision = 1L
    private var serial = 0
    private val plans = mutableMapOf<String, Pair<String, JsonObject>>()
    private var job: JsonObject? = null
    private var jobOwner: String? = null
    private var appliedPlan: JsonObject? = null
    private var poll = 0
    private val versions = mutableMapOf<String, String>()

    fun reply(action: String, payload: JsonObject, owner: String): JsonObject = when (action) {
        "engines.status" -> status(owner)
        "engines.plan" -> plan(payload, owner)
        "engines.apply" -> apply(payload, owner)
        "engines.job" -> readJob(payload, owner)
        else -> failure("invalid_request")
    }

    private fun status(owner: String) = buildJsonObject {
        put("schema", 1); put("routerId", router); put("revision", revision); put("catalogSha256", catalog)
        put("activeEngineId", activeProtocol()?.let(::JsonPrimitive) ?: JsonNull)
        put("activeProfileId", if (activeProtocol() == null) JsonNull else JsonPrimitive("server-demo-active"))
        put("entries", JsonArray(RouterInstallProtocol.entries.map { protocol -> buildJsonObject {
            val present = protocol.canonical in installed()
            put("id", protocol.canonical); put("availability", if (protocol.canonical in available) "AVAILABLE" else "NOT_RELEASED")
            put("installed", present); put("storage", if (present) JsonPrimitive("FLASH") else JsonNull)
            put("version", if (present) JsonPrimitive(versions[protocol.canonical] ?: "1.0.0-demo") else JsonNull)
            put("binarySha256", if (present) JsonPrimitive(hash("synthetic-only-" + protocol.canonical)) else JsonNull)
            put("active", present && protocol.canonical == activeProtocol())
            put("updateAvailable", present && protocol.canonical !in versions)
            put("archiveBytes", if (protocol.canonical in available) JsonPrimitive(1_048_576) else JsonNull)
            put("installedBytes", if (present) JsonPrimitive(2_097_152) else JsonNull)
        } }))
        val running = job?.text("state") !in setOf(null, "SUCCEEDED", "FAILED", "RECOVERY_REQUIRED")
        put("busy", running)
        put("activeOwnJob", if (running && owner == jobOwner) ref(requireNotNull(job)) else JsonNull)
        put("lastOwnJob", if (owner == jobOwner) job?.let(::ref) ?: JsonNull else JsonNull)
    }

    private fun plan(payload: JsonObject, owner: String): JsonObject {
        if (payload.number("expectedRevision") != revision || payload.text("catalogSha256") != catalog) return failure("stale_plan")
        if (job?.text("state") !in setOf(null, "SUCCEEDED", "FAILED", "RECOVERY_REQUIRED")) return failure("busy")
        val ids = (payload["engineIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (ids.isEmpty() || ids.distinct().size != ids.size || ids.any { name -> RouterInstallProtocol.entries.none { it.canonical == name } }) return failure("unknown_protocol")
        if (ids.any { it !in available }) return failure("not_released")
        val op = payload.text("operation")
        if (op !in setOf("ADD", "UPDATE", "REMOVE")) return failure("invalid_request")
        if (op == "REMOVE" && activeProtocol() in ids) return failure("in_use")
        if (op == "ADD" && ids.any { it in installed() } || op != "ADD" && ids.any { it !in installed() }) return failure("invalid_request")
        val id = "plan-demo-" + (++serial)
        val impact = if (op == "UPDATE" && activeProtocol() in ids) "RECONNECT_REQUIRED" else "KEEP_ACTIVE"
        val value = buildJsonObject {
            put("schema", 1); put("routerId", router); put("planId", id); put("planSha256", hash(id))
            put("revision", revision); put("bootId", "boot-demo"); put("expiresAtUptimeMs", 300_000); put("expiresAtEpochMs", JsonNull)
            put("operation", op); put("engineIds", JsonArray(ids.map(::JsonPrimitive)))
            put("changes", JsonArray(ids.map { protocol -> buildJsonObject {
                put("id", protocol); put("fromVersion", if (protocol in installed()) JsonPrimitive(versions[protocol] ?: "1.0.0-demo") else JsonNull)
                put("toVersion", if (op == "REMOVE") JsonNull else JsonPrimitive("1.0.1-demo")); put("storage", "FLASH")
                put("archiveBytes", if (op == "REMOVE") 0 else 1_048_576); put("installedBytes", if (op == "REMOVE") 0 else 2_097_152)
            } }))
            put("budget", buildJsonObject {
                put("downloadBytes", if (op == "REMOVE") 0 else ids.size * 1_048_576L); put("stagingBytes", 4_194_304)
                put("flashRequiredBytes", 4_194_304); put("flashAvailableBytes", 67_108_864)
                put("ramRequiredBytes", 8_388_608); put("ramAvailableBytes", 134_217_728); put("reservedBytes", 16_777_216)
            })
            put("impact", impact); put("confirmationRequired", true)
            put("warnings", JsonArray(if (impact == "RECONNECT_REQUIRED") listOf(JsonPrimitive(impact)) else emptyList()))
        }
        plans[id] = owner to value
        return value
    }

    private fun apply(payload: JsonObject, owner: String): JsonObject {
        val id = payload.text("planId") ?: return failure("invalid_request")
        val selected = plans[id]?.takeIf { it.first == owner }?.second ?: return failure("stale_plan")
        if (payload["confirmed"] != JsonPrimitive(true) || payload.text("planSha256") != selected.text("planSha256") || payload.number("expectedRevision") != revision) return failure("stale_plan")
        if (job?.text("state") !in setOf(null, "SUCCEEDED", "FAILED", "RECOVERY_REQUIRED")) return failure("busy")
        plans.remove(id)
        jobOwner = owner; appliedPlan = selected; poll = 0
        job = jobValue("QUEUED", 0, "PREPARING", "job-demo-" + serial, id)
        return requireNotNull(job)
    }

    private fun readJob(payload: JsonObject, owner: String): JsonObject {
        val current = job?.takeIf { jobOwner == owner && it.text("jobId") == payload.text("jobId") } ?: return failure("stale_session")
        if (current.text("state") == "SUCCEEDED") return current
        val states = listOf(Triple("DOWNLOADING", 20, "DOWNLOADING_PACKAGE"), Triple("VERIFYING", 40, "VERIFYING_PACKAGE"),
            Triple("INSTALLING", 65, "INSTALLING_PACKAGE"), Triple("CHECKING", 85, "CHECKING_ENGINE"), Triple("SUCCEEDED", 100, "COMPLETED"))
        val phase = states[(poll++).coerceAtMost(states.lastIndex)]
        if (phase.first == "SUCCEEDED") {
            val plan = requireNotNull(appliedPlan)
            val ids = (plan["engineIds"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
            replaceInstalled(if (plan.text("operation") == "REMOVE") installed() - ids else installed() + ids)
            ids.forEach { versions[it] = "1.0.1-demo" }
            revision++
        }
        job = jobValue(phase.first, phase.second, phase.third, requireNotNull(current.text("jobId")), requireNotNull(current.text("planId")))
        return requireNotNull(job)
    }

    private fun jobValue(state: String, percent: Int, step: String, id: String, plan: String) = buildJsonObject {
        put("schema", 1); put("routerId", router); put("jobId", id); put("planId", plan); put("state", state)
        put("progressPercent", percent); put("step", step); put("revision", revision); put("failureReason", JsonNull)
        put("previousConnectionRestored", JsonNull)
    }
    private fun ref(value: JsonObject) = JsonObject(value.filterKeys { it in setOf("jobId", "planId", "state", "progressPercent", "step") })
    private fun failure(reason: String) = buildJsonObject { put("_foxhole_error", "engine_" + reason) }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.longOrNull
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
