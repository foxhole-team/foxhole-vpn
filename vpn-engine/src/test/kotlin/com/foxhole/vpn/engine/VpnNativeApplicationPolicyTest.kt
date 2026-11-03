package com.foxhole.vpn.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnNativeApplicationPolicyTest {
    private val compiler = VpnNativeApplicationPolicy("""{"dns":{"mode":"real_ip","route":"primary","advertise":"1.1.1.1","upstreams":[{"type":"udp","address":"1.1.1.1:53"}]},"outbound":{"type":"fixture"}}""")

    @Test fun nativePolicyNeverCreatesApplicationRoutingOrDirectDefault() {
        val traffic = Json.parseToJsonElement(compiler.engine()).jsonObject.getValue("traffic").jsonObject
        assertEquals("vpn", traffic.getValue("default_action").jsonPrimitive.content)
        assertFalse("applications" in traffic)
        assertFalse("continuity" in traffic)
    }

    @Test fun barrierIsExplicitAndRevisionBoundWithoutChangingProfileDns() {
        val policy = Json.parseToJsonElement(compiler.policy(true, 7)).jsonObject
        assertTrue(policy.getValue("traffic").jsonObject.getValue("kill_switch").jsonPrimitive.boolean)
        assertEquals(7L, policy.getValue("expected_revision").jsonPrimitive.longOrNull)
        assertEquals("primary", policy.getValue("dns").jsonObject.getValue("route").jsonPrimitive.content)
        assertFalse(Json.parseToJsonElement(compiler.policy(false, 8)).jsonObject.getValue("traffic").jsonObject.getValue("kill_switch").jsonPrimitive.boolean)
    }

    @Test fun wireGuardDnsRemainsRawAndDoesNotCreateAnUnsupportedInterceptor() {
        val compiler = VpnNativeApplicationPolicy("""{"dns":{"mode":"real_ip"},"outbound":{"type":"fixture"}}""")
        val dns = Json.parseToJsonElement(compiler.engine(true)).jsonObject.getValue("dns").jsonObject
        assertEquals(setOf("mode"), dns.keys)
        assertEquals(dns, Json.parseToJsonElement(compiler.policy(false, 4)).jsonObject.getValue("dns"))
    }
}
