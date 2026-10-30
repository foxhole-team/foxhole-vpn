package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterInstallCatalog
import com.foxhole.vpn.router.RouterInstallProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Synthetic coordinator inputs only. These hashes never identify release packages. */
internal fun routerReviewCatalog(): RouterInstallCatalog {
    val combinations = RouterInstallProtocol.entries.map { setOf(it) } + listOf(
        setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD),
        setOf(RouterInstallProtocol.HYSTERIA2, RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD),
        RouterInstallProtocol.entries.toSet(),
    )
    val document = buildJsonObject {
        put("schema", 1)
        // The existing parser requires this field. This source-set-only catalog is simulation data.
        put("development", false)
        put("defaultProtocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
        put("allProtocols", JsonArray(RouterInstallProtocol.entries.map { JsonPrimitive(it.canonical) }))
        put(
            "variants",
            JsonArray(
                combinations.mapIndexed { index, protocols ->
                    buildJsonObject {
                        put("id", "demo-$index")
                        put("architecture", "x86_64")
                        put("supportedOpenWrt", JsonArray(listOf(JsonPrimitive("25.12.5"))))
                        put("supportedTargets", JsonArray(listOf(JsonPrimitive("x86/64"))))
                        put("protocols", JsonArray(protocols.map { JsonPrimitive(it.canonical) }))
                        put("coreProducerFile", "demo-producer-$index.json")
                        put("coreProducerSha256", reviewDigest("demo-producer-$index"))
                        put("coreBinarySha256", reviewDigest("demo-core-$index"))
                        put("coreSourceSha256", reviewDigest("demo-source-$index"))
                        put("capabilitiesFile", "demo-capabilities-$index.json")
                        put("capabilitiesSha256", reviewDigest("demo-capabilities-$index"))
                        put(
                            "modes",
                            buildJsonObject {
                                for (mode in listOf("flash", "ram")) {
                                    put(
                                        mode,
                                        buildJsonObject {
                                            put("budgetFile", "demo-$mode-$index.json")
                                            put("budgetSha256", reviewDigest("demo-$mode-$index"))
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            ),
        )
    }.toString().toByteArray()
    return RouterInstallCatalog.parseVerified(
        document,
        reviewDigest(document),
        reviewDigest("offline-demo-manifest"),
    )
}

internal fun reviewDigest(value: String): String = reviewDigest(value.toByteArray())

private fun reviewDigest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal const val DEMO_PROFILE_URI =
    "hysteria2://offline-demo@1.1.1.1:443?sni=1.1.1.1#Demo"

internal const val DEMO_VLESS_URI =
    "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443" +
        "?encryption=none&security=tls&sni=1.1.1.1&type=grpc&serviceName=Demo&mode=gun#Demo"
