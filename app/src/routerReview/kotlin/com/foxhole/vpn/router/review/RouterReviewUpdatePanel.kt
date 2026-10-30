package com.foxhole.vpn.router.review

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Offline UI simulation only. It never downloads, verifies or installs a real package. */
internal class RouterReviewUpdatePanel(private val installed: () -> Set<String>) {
    private val router = hash("synthetic-review-update-router")
    private var revision = 1L
    private var serial = 0
    private var checked = false
    private var updated = false
    private var automatic = false
    private var plan: JsonObject? = null
    private var planOwner: String? = null
    private var job: JsonObject? = null
    private var jobOwner: String? = null
    private var polls = 0

    fun reply(action: String, payload: JsonObject, owner: String): JsonObject = when (action) {
        "updates.status" -> status(owner)
        "updates.check" -> if (!revisionMatches(payload) || running()) failure("busy") else start("CHECK", owner, null)
        "updates.plan" -> prepare(payload, owner)
        "updates.apply" -> {
            val selected = plan
            if (running()) failure("busy")
            else if (selected == null || planOwner != owner || !revisionMatches(payload) || payload["confirmed"] != JsonPrimitive(true) ||
                selected["planId"] != payload["planId"] || selected["planSha256"] != payload["planSha256"]) failure("stale_plan")
            else { plan = null; start("APPLY", owner, selected["planId"]) }
        }
        "updates.job" -> {
            val current = job
            if (current == null || jobOwner != owner || current["jobId"] != payload["jobId"]) failure("stale_session")
            else advance(current)
        }
        "updates.settings" -> {
            val enabled = (payload["autoUpdateEnabled"] as? JsonPrimitive)?.booleanOrNull
            if (!revisionMatches(payload) || running() || enabled == null) failure("stale_plan")
            else { automatic = enabled; revision++; plan = null; status(owner) }
        }
        else -> failure("invalid_request")
    }
    private fun status(owner: String) = buildJsonObject {
        put("schema", 1); put("routerId", router); put("revision", revision); put("supported", true)
        put("currentVersion", if (updated) "1.0.1-demo" else "1.0.0-demo")
        put("availableVersion", if (checked) JsonPrimitive("1.0.1-demo") else JsonNull)
        put("availability", if (!checked) "NOT_CHECKED" else if (updated) "CURRENT" else "AVAILABLE")
        put("lastCheckedAtUnixMs", if (checked) JsonPrimitive(1_000) else JsonNull); put("failureReason", JsonNull)
        put("busy", running()); put("autoUpdate", buildJsonObject {
            put("enabled", automatic); put("intervalSeconds", 86400); put("lastAttemptAtUnixMs", JsonNull)
            put("nextCheckAtUnixMs", JsonNull); put("suspendedReason", JsonNull)
        })
        put("installedModules", JsonArray(selected().map { id -> buildJsonObject {
            put("id", id); put("storage", "FLASH"); put("version", "1.0.0-demo")
        } }))
        put("activeOwnJob", if (running() && jobOwner == owner) ref(requireNotNull(job)) else JsonNull)
        put("lastOwnJob", if (jobOwner == owner) job?.let(::ref) ?: JsonNull else JsonNull)
        put("activeAutomaticJob", JsonNull); put("simulation", true)
    }
    private fun prepare(payload: JsonObject, owner: String): JsonObject {
        if (!revisionMatches(payload) || !checked || updated || running()) return failure("stale_plan")
        val id = "update-plan-" + (++serial).toString(16).padStart(32, '0')
        val value = buildJsonObject {
            put("schema", 1); put("routerId", router); put("revision", revision); put("planId", id); put("planSha256", hash(id))
            put("fromVersion", "1.0.0-demo"); put("toVersion", "1.0.1-demo"); put("engineIds", JsonArray(selected().map(::JsonPrimitive)))
            put("changes", JsonArray(listOf(buildJsonObject {
                put("id", "foxhole"); put("fromVersion", "1.0.0-demo"); put("toVersion", "1.0.1-demo"); put("storage", JsonNull)
            })))
            put("impact", "RECONNECT_REQUIRED"); put("confirmationRequired", true); put("expiresAtUptimeMs", 300_000); put("bootId", "synthetic-review-boot")
            put("budget", buildJsonObject {
                put("downloadBytes", 1_048_576); put("stagingBytes", 2_097_152); put("flashRequiredBytes", 2_097_152)
                put("flashAvailableBytes", 67_108_864); put("ramRequiredBytes", 2_097_152); put("ramAvailableBytes", 134_217_728); put("reservedBytes", 16_777_216)
            }); put("simulation", true)
        }
        plan = value; planOwner = owner
        return value
    }
    private fun start(operation: String, owner: String, planId: JsonElement?): JsonObject {
        polls = 0; jobOwner = owner
        job = buildJsonObject {
            put("schema", 1); put("routerId", router); put("jobId", "update-job-" + (++serial).toString(16).padStart(32, '0'))
            put("planId", planId ?: JsonNull); put("operation", operation); put("automatic", false)
            put("state", "QUEUED"); put("step", "PREPARING"); put("progressPercent", 0); put("revision", revision)
            put("failureReason", JsonNull); put("previousConnectionRestored", JsonNull); put("simulation", true)
        }
        return requireNotNull(job)
    }
    private fun advance(current: JsonObject): JsonObject {
        if (!running()) return current
        val checking = current["operation"] == JsonPrimitive("CHECK")
        val phases = if (checking) listOf(Triple("CHECKING", "CHECKING_RELEASE", 40), Triple("SUCCEEDED", "COMPLETED", 100))
            else listOf(Triple("DOWNLOADING", "DOWNLOADING_PACKAGE", 20), Triple("VERIFYING", "VERIFYING_PACKAGE", 40),
                Triple("INSTALLING", "INSTALLING_PACKAGE", 60), Triple("RECONNECTING", "RECONNECTING_VPN", 85), Triple("SUCCEEDED", "COMPLETED", 100))
        val phase = phases[(polls++).coerceAtMost(phases.lastIndex)]
        if (phase.first == "SUCCEEDED") { if (checking) checked = true else updated = true; revision++ }
        job = JsonObject(current + mapOf("state" to JsonPrimitive(phase.first), "step" to JsonPrimitive(phase.second),
            "progressPercent" to JsonPrimitive(phase.third), "revision" to JsonPrimitive(revision)))
        return requireNotNull(job)
    }
    private fun selected() = installed().intersect(setOf("vless", "hysteria2", "wireguard")).sorted()
    private fun running() = job?.get("state")?.jsonPrimitive?.content !in setOf(null, "SUCCEEDED", "FAILED", "RECOVERY_REQUIRED")
    private fun ref(value: JsonObject) = JsonObject(value.filterKeys { it in setOf("jobId", "planId", "operation", "automatic", "state", "step", "progressPercent") })
    private fun revisionMatches(payload: JsonObject) = (payload["expectedRevision"] as? JsonPrimitive)?.longOrNull == revision
    private fun failure(reason: String) = buildJsonObject { put("_foxhole_error", "update_" + reason) }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
