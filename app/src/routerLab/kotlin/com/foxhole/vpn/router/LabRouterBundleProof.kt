package com.foxhole.vpn.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun validateLabRouterBundleProof(value: JsonObject, manifestSha256: String): JsonObject {
    require(value.keys == setOf("schema", "scope", "status", "manifestSha256", "bootId", "mountDevice", "bindings"))
    require(value.installNumber("schema") == 1 && value.installText("scope") == "isolated-qa-only" && value.installText("status") == "accepted")
    require(value.installText("manifestSha256") == manifestSha256 && manifestSha256.matches(Regex("[a-f0-9]{64}")))
    require(value.installText("bootId").matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
    require(value.installText("mountDevice").matches(Regex("[A-Za-z0-9_/:.-]{1,128}")))
    val bindings = value["bindings"] as? JsonArray ?: error("lab_bundle_bindings")
    require(bindings.size in 1..64 && bindings.all { it is JsonObject } && bindings.distinct().size == bindings.size)
    return value
}

internal fun verifyLabRouterBundleBinding(proof: JsonObject, payload: RouterSetupPayload, proposal: RouterInstallProposal) {
    require(proposal.manifestSha256 == proof.installText("manifestSha256"))
    val expected = if (payload.compact != null) compactLabBinding(payload, proposal)
    else legacyLabBinding(payload, proposal)
    val bindings = proof["bindings"] as? JsonArray ?: error("lab_bundle_bindings")
    require(bindings.count { it == expected } == 1)
}

private fun legacyLabBinding(payload: RouterSetupPayload, proposal: RouterInstallProposal): JsonObject {
    val binding = proposal.binding
    require(binding.compact == null)
    val variant = requireNotNull(payload.variant)
    require(variant.id == binding.variantId && variant.architecture == binding.architecture && binding.target in variant.supportedTargets &&
        proposal.openWrtVersion in variant.supportedOpenWrt && variant.protocols == binding.selection.protocols &&
        variant.coreProducerSha256 == binding.coreProducerSha256 && variant.coreBinarySha256 == binding.coreBinarySha256 &&
        variant.coreSourceSha256 == binding.coreSourceSha256 && variant.capabilitiesSha256 == binding.capabilitiesSha256 &&
        variant.modes[binding.storageMode]?.sha256 == binding.budgetSha256)
    return buildJsonObject {
        put("catalogSha256", binding.selection.catalogSha256)
        put("variantId", binding.variantId); put("architecture", binding.architecture); put("target", binding.target)
        put("mode", if (binding.storageMode == RouterStorageMode.FLASH) "flash" else "ram")
        put("coreProducerSha256", binding.coreProducerSha256); put("coreBinarySha256", binding.coreBinarySha256)
        put("coreSourceSha256", binding.coreSourceSha256); put("capabilitiesSha256", binding.capabilitiesSha256)
        put("budgetSha256", binding.budgetSha256)
        put("protocols", JsonArray(binding.selection.protocols.map { it.canonical }.sorted().map(::JsonPrimitive)))
    }
}

private fun compactLabBinding(payload: RouterSetupPayload, proposal: RouterInstallProposal): JsonObject {
    require(payload.variant == null)
    val binding = proposal.binding
    val compact = requireNotNull(binding.compact)
    val plan = requireNotNull(payload.compact?.modes?.get(binding.storageMode))
    require(plan.binding == compact)
    val platform = compact.platform
    require(binding.variantId == platform.id && binding.architecture == platform.architecture &&
        binding.target in platform.supportedTargets && proposal.openWrtVersion in platform.supportedOpenWrt &&
        binding.storageMode in platform.modes(binding.selection.protocols, binding.selection.includeServer))
    require(listOf(binding.coreProducerSha256, binding.coreBinarySha256, binding.coreSourceSha256,
        binding.capabilitiesSha256).all { it == null })
    val modules = platform.clients.filter { it.protocol in binding.selection.protocols && it.storage == binding.storageMode }
        .sortedBy { it.protocol.canonical }
    require(modules.isNotEmpty() && compact.modules == modules)
    val server = compact.server
    require((server != null) == binding.selection.includeServer)
    if (server != null) {
        val released = requireNotNull(platform.server)
        val declared = requireNotNull(released.modes[binding.storageMode])
        require(server.compactText("name") == declared.name && server.compactFile("file") == declared.file &&
            server.compactHash("sha256") == declared.sha256 && server.compactHash("producerSha256") == released.producer.sha256)
    }
    val document = plan.document
    require(binding.budgetSha256 == compact.budgetSha256 && binding.budgetSha256.matches(COMPACT_HASH) &&
        routerReleaseSha256(document.toString().toByteArray()) == binding.budgetSha256)
    require(document.compactLong("schema") == 2L && document.compactText("kind") == "compact-selected-budget-v1" &&
        document.compactText("architecture") == platform.architecture && document.compactText("mode") == binding.storageMode.name.lowercase() &&
        document.compactHash("engineCatalogSha256") == platform.engineCatalog.sha256 &&
        document.compactBoolean("includeServer") == binding.selection.includeServer &&
        document.compactStrings("protocols", 1..12) == binding.selection.protocols.map { it.canonical }.sorted() &&
        document["modules"] == JsonArray(modules.map(::compactClientJson)) && document["server"] == (server ?: JsonNull))
    return buildJsonObject {
        put("schema", 2); put("kind", "compact-install-binding-v1")
        put("catalogSha256", binding.selection.catalogSha256); put("platformId", platform.id)
        put("architecture", binding.architecture); put("target", binding.target)
        put("mode", binding.storageMode.name.lowercase()); put("budgetSha256", binding.budgetSha256)
        put("engineCatalogSha256", platform.engineCatalog.sha256); put("packageUniverseSha256", platform.packageUniverse.sha256)
        put("protocols", JsonArray(binding.selection.protocols.map { it.canonical }.sorted().map(::JsonPrimitive)))
        put("includeServer", binding.selection.includeServer)
        put("modules", JsonArray(modules.map(::compactClientJson))); put("server", server ?: JsonNull)
    }
}

internal fun verifyLabRouterBundleReadback(actual: JsonObject, proof: JsonObject, manifestSha256: String) {
    require(actual.keys == setOf("schema", "bootId", "mountDevice", "manifestSha256", "rootOwned", "readOnly"))
    require(actual.installNumber("schema") == 1 && actual.labBundleBoolean("rootOwned") && actual.labBundleBoolean("readOnly"))
    require(actual.installText("bootId") == proof.installText("bootId") && actual.installText("mountDevice") == proof.installText("mountDevice"))
    require(actual.installText("manifestSha256") == manifestSha256 && proof.installText("manifestSha256") == manifestSha256)
}

private fun JsonObject.labBundleBoolean(key: String): Boolean =
    requireNotNull((get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)
