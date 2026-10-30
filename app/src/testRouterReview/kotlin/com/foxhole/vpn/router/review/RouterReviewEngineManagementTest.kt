package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterEngineOperation
import com.foxhole.vpn.router.RouterEngineImpact
import com.foxhole.vpn.router.RouterEngineJobState
import com.foxhole.vpn.router.RouterInstallProtocol
import com.foxhole.vpn.router.parseRouterEngineStatus
import com.foxhole.vpn.router.parseRouterEnginePlan
import com.foxhole.vpn.router.parseRouterEngineJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterReviewEngineManagementTest {
    @Test fun anExplicitSyntheticAddChangesOnlyTheChosenProtocolAfterTheJobFinishes() {
        var installed = setOf("hysteria2")
        val panel = RouterReviewEnginePanel({ installed }, { installed = it }, { "hysteria2" })
        val status = parseRouterEngineStatus(panel.reply("engines.status", JsonObject(emptyMap()), "pair-demo"))
        val plan = parseRouterEnginePlan(panel.reply("engines.plan", request("ADD", "vless", status.catalogSha256), "pair-demo"),
            status, RouterEngineOperation.ADD, setOf(RouterInstallProtocol.VLESS))
        assertEquals(setOf("hysteria2"), installed)
        assertEquals(RouterEngineImpact.KEEP_ACTIVE, plan.impact)
        var job = parseRouterEngineJob(panel.reply("engines.apply", buildJsonObject {
            put("planId", plan.planId); put("planSha256", plan.planSha256); put("expectedRevision", 1); put("confirmed", true)
        }, "pair-demo"), status.routerId, planId = plan.planId)
        repeat(5) {
            job = parseRouterEngineJob(panel.reply("engines.job", buildJsonObject { put("jobId", job.jobId) }, "pair-demo"), status.routerId, jobId = job.jobId)
        }
        assertEquals(RouterEngineJobState.SUCCEEDED, job.state)
        assertEquals(setOf("hysteria2", "vless"), installed)
        assertFalse(parseRouterEngineStatus(panel.reply("engines.status", JsonObject(emptyMap()), "pair-demo")).busy)
    }

    @Test fun activeUpdateRequiresReconnectAndActiveRemovalIsRefused() {
        val panel = RouterReviewEnginePanel({ setOf("hysteria2") }, {}, { "hysteria2" })
        val status = parseRouterEngineStatus(panel.reply("engines.status", JsonObject(emptyMap()), "pair-demo"))
        val plan = parseRouterEnginePlan(panel.reply("engines.plan", request("UPDATE", "hysteria2", status.catalogSha256), "pair-demo"),
            status, RouterEngineOperation.UPDATE, setOf(RouterInstallProtocol.HYSTERIA2))
        assertEquals(RouterEngineImpact.RECONNECT_REQUIRED, plan.impact)
        val refused = panel.reply("engines.plan", request("REMOVE", "hysteria2", status.catalogSha256), "pair-demo")
        assertEquals(JsonPrimitive("engine_in_use"), refused["_foxhole_error"])
    }

    @Test fun recognizedButUnreleasedProtocolsDoNotBecomeInstallable() {
        val panel = RouterReviewEnginePanel({ setOf("hysteria2") }, {}, { null })
        val status = parseRouterEngineStatus(panel.reply("engines.status", JsonObject(emptyMap()), "pair-demo"))
        assertEquals(12, status.entries.size)
        assertEquals(2, status.entries.count { it.canAdd })
        val result = panel.reply("engines.plan", request("ADD", "tuic", status.catalogSha256), "pair-demo")
        assertEquals(JsonPrimitive("engine_not_released"), result["_foxhole_error"])
    }

    @Test fun anotherPairingCannotConsumeTheOwnersPlan() {
        val panel = RouterReviewEnginePanel({ setOf("hysteria2") }, {}, { null })
        val status = parseRouterEngineStatus(panel.reply("engines.status", JsonObject(emptyMap()), "owner"))
        val plan = panel.reply("engines.plan", request("ADD", "vless", status.catalogSha256), "owner")
        val apply = buildJsonObject { put("planId", plan["planId"]!!); put("planSha256", plan["planSha256"]!!); put("expectedRevision", 1); put("confirmed", true) }
        assertTrue("_foxhole_error" in panel.reply("engines.apply", apply, "other"))
        assertFalse("_foxhole_error" in panel.reply("engines.apply", apply, "owner"))
        val other = parseRouterEngineStatus(panel.reply("engines.status", JsonObject(emptyMap()), "other"))
        assertTrue(other.busy)
        assertEquals(null, other.activeOwnJob)
    }

    private fun request(operation: String, id: String, catalog: String) = buildJsonObject {
        put("operation", operation); put("engineIds", JsonArray(listOf(JsonPrimitive(id)))); put("storage", "AUTO")
        put("expectedRevision", 1); put("catalogSha256", catalog)
    }
}
