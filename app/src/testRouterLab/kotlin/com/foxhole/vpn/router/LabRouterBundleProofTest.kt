package com.foxhole.vpn.router

import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LabRouterBundleProofTest {
    private val manifest = "a".repeat(64)
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val selection = RouterInstallProtocolSelection("b".repeat(64), setOf(RouterInstallProtocol.HYSTERIA2))
    private val variant = RouterInstallVariant("x86-hy2", "x86_64", setOf("25.12.5"), setOf("x86/64"), selection.protocols,
        RouterInstallAsset("core-producer.json", "c".repeat(64)), "d".repeat(64), "e".repeat(64),
        RouterInstallAsset("capabilities.json", "f".repeat(64)), mapOf(RouterStorageMode.RAM to RouterInstallAsset("budget-ram.json", "1".repeat(64))))
    private val binding = RouterInstallBinding(selection, variant.id, variant.architecture, RouterStorageMode.RAM,
        variant.coreProducerSha256, variant.coreBinarySha256, variant.coreSourceSha256, variant.capabilitiesSha256, "1".repeat(64), "x86/64")

    private fun proposal() = RouterInstallProposal("25.12.5", "x86_64", RouterStorageMode.RAM, manifest, AutoCloseable { }, binding)
    private fun payload(value: RouterInstallVariant = variant): RouterSetupPayload {
        val bytes = "#!/bin/sh\nexit 0\n".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return RouterSetupPayload(value, emptyMap(), RouterVerifiedInstallScript(bytes, sha),
            "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0", emptyMap())
    }
    private fun bindingDocument() = buildJsonObject {
        put("catalogSha256", selection.catalogSha256); put("variantId", variant.id)
        put("architecture", variant.architecture); put("target", "x86/64"); put("mode", "ram")
        put("coreProducerSha256", variant.coreProducerSha256); put("coreBinarySha256", variant.coreBinarySha256)
        put("coreSourceSha256", variant.coreSourceSha256); put("capabilitiesSha256", variant.capabilitiesSha256)
        put("budgetSha256", "1".repeat(64)); put("protocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
    }
    private fun proof() = buildJsonObject {
        put("schema", 1); put("scope", "isolated-qa-only"); put("status", "accepted"); put("manifestSha256", manifest)
        put("bootId", boot); put("mountDevice", "/dev/vdb"); put("bindings", JsonArray(listOf(bindingDocument())))
    }
    private fun facts() = buildJsonObject {
        put("schema", 1); put("rootOwned", true); put("readOnly", true); put("bootId", boot)
        put("mountDevice", "/dev/vdb"); put("manifestSha256", manifest)
    }
    private fun changed(value: JsonObject, key: String, replacement: JsonPrimitive): JsonObject = JsonObject(value + (key to replacement))

    @Test fun exactSyntheticFixtureContractBindsCurrentBootManifestPayloadAndSelectedRamBudget() {
        val proof = validateLabRouterBundleProof(proof(), manifest)
        verifyLabRouterBundleBinding(proof, payload(), proposal())
        verifyLabRouterBundleReadback(facts(), proof, manifest)
        assertEquals("ram", (bindingDocument()["mode"] as JsonPrimitive).content)
    }

    @Test fun aDifferentPayloadCannotReuseAnAcceptedSelectedBinding() {
        for (value in listOf(variant.copy(coreBinarySha256 = "2".repeat(64)), variant.copy(coreSourceSha256 = "2".repeat(64)),
            variant.copy(capabilities = RouterInstallAsset("capabilities.json", "2".repeat(64))),
            variant.copy(protocols = setOf(RouterInstallProtocol.VLESS)), variant.copy(supportedTargets = setOf("armsr/armv8")),
            variant.copy(modes = mapOf(RouterStorageMode.RAM to RouterInstallAsset("budget-ram.json", "2".repeat(64)))))) {
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(proof(), payload(value), proposal()) }
        }
    }

    @Test fun aNewBootMountOrManifestCannotBeReadBackAsTheAcceptedFixture() {
        for ((key, value) in listOf("bootId" to "12345678-1234-1234-1234-123456789abd", "mountDevice" to "/dev/vdc", "manifestSha256" to "2".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleReadback(changed(facts(), key, JsonPrimitive(value)), proof(), manifest) }
        }
    }

    @Test fun readWriteNonRootAndStringBooleanFactsNeverAuthorizeTheLocalInstaller() {
        for (key in listOf("rootOwned", "readOnly")) for (value in listOf(JsonPrimitive(false), JsonPrimitive("true"))) {
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleReadback(changed(facts(), key, value), proof(), manifest) }
        }
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleReadback(JsonObject(facts() + ("unexpected" to JsonPrimitive(true))), proof(), manifest) }
    }

    @Test fun proofMustHaveTheExactPrivateScopeAndCurrentManifest() {
        for ((key, value) in listOf("scope" to "production", "status" to "design-only", "manifestSha256" to "2".repeat(64), "bootId" to "not-a-boot")) {
            assertThrows(IllegalArgumentException::class.java) { validateLabRouterBundleProof(changed(proof(), key, JsonPrimitive(value)), manifest) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateLabRouterBundleProof(JsonObject(proof() + ("unexpected" to JsonPrimitive(true))), manifest) }
    }

    @Test fun duplicateNonObjectAndMissingSelectionBindingsAreRefused() {
        val original = proof()
        for (bindings in listOf(JsonArray(listOf(bindingDocument(), bindingDocument())), JsonArray(listOf(JsonPrimitive("unbound"))), JsonArray(emptyList()))) {
            assertThrows(IllegalArgumentException::class.java) { validateLabRouterBundleProof(JsonObject(original + ("bindings" to bindings)), manifest) }
        }
        val other = JsonObject(bindingDocument() + ("budgetSha256" to JsonPrimitive("2".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(JsonObject(original + ("bindings" to JsonArray(listOf(other)))), payload(), proposal()) }
    }

    private data class CompactContext(
        val fixture: RouterCompactBootstrapFixtures,
        val payload: RouterSetupPayload,
        val proposal: RouterInstallProposal,
    )

    private suspend fun compactContext(mode: RouterStorageMode, server: Boolean = false): CompactContext {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val selected = fixture.selection(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD, server = server)
        val payload = fixture.release.payload(fixture.system, selected)
        val compact = requireNotNull(payload.compact).modes.getValue(mode).binding
        val binding = RouterInstallBinding(selected, compact.platform.id, compact.platform.architecture, mode,
            null, null, null, null, compact.budgetSha256, "x86/64", compact)
        return CompactContext(fixture, payload, compactProposal(fixture, binding))
    }

    private fun compactProposal(fixture: RouterCompactBootstrapFixtures, binding: RouterInstallBinding,
        version: String = "25.12.5"): RouterInstallProposal =
        RouterInstallProposal(version, binding.architecture, binding.storageMode, fixture.manifestSha256, AutoCloseable { }, binding)

    private fun compactDocument(binding: RouterInstallBinding) = buildJsonObject {
        val compact = requireNotNull(binding.compact)
        put("schema", 2); put("kind", "compact-install-binding-v1")
        put("catalogSha256", binding.selection.catalogSha256); put("platformId", compact.platform.id)
        put("architecture", binding.architecture); put("target", binding.target); put("mode", binding.storageMode.name.lowercase())
        put("budgetSha256", binding.budgetSha256); put("engineCatalogSha256", compact.platform.engineCatalog.sha256)
        put("packageUniverseSha256", compact.platform.packageUniverse.sha256)
        put("protocols", compactArray(binding.selection.protocols.map { it.canonical }.sorted())); put("includeServer", binding.selection.includeServer)
        put("modules", JsonArray(compact.modules.map(::compactClientJson))); put("server", compact.server ?: JsonNull)
    }

    private fun compactProof(context: CompactContext, entry: JsonObject = compactDocument(context.proposal.binding)): JsonObject =
        validateLabRouterBundleProof(JsonObject(proof() + mapOf("manifestSha256" to JsonPrimitive(context.fixture.manifestSha256),
            "bindings" to JsonArray(listOf(entry)))), context.fixture.manifestSha256)

    private fun rebound(context: CompactContext, document: JsonObject,
        modules: List<RouterCompactClient> = requireNotNull(context.proposal.binding.compact).modules,
        server: JsonObject? = requireNotNull(context.proposal.binding.compact).server): CompactContext {
        val mode = context.proposal.storageMode
        val current = requireNotNull(context.payload.compact)
        val plan = current.modes.getValue(mode)
        val digest = routerReleaseSha256(document.toString().toByteArray())
        val compact = plan.binding.copy(modules = modules, server = server, budgetSha256 = digest)
        val payload = context.payload.copy(compact = RouterCompactPayload(current.modes + (mode to plan.copy(document = document, binding = compact))))
        val binding = context.proposal.binding.copy(compact = compact, budgetSha256 = digest)
        return context.copy(payload = payload, proposal = compactProposal(context.fixture, binding))
    }

    @Test fun compactSelectedFlashAndRamProofsBindExactIndependentModulesWithoutImplicitServer() = runTest {
        for (mode in RouterStorageMode.entries) {
            val context = compactContext(mode)
            verifyLabRouterBundleBinding(compactProof(context), context.payload, context.proposal)
            assertEquals(setOf("vless", "wireguard"), requireNotNull(context.proposal.binding.compact).modules.map { it.protocol.canonical }.toSet())
            assertEquals(JsonNull, compactDocument(context.proposal.binding)["server"])
        }
    }

    @Test fun compactExplicitServerRemainsAnIndependentExactRoleInBothModes() = runTest {
        for (mode in RouterStorageMode.entries) {
            val context = compactContext(mode, server = true)
            verifyLabRouterBundleBinding(compactProof(context), context.payload, context.proposal)
            assertEquals(JsonPrimitive(true), compactDocument(context.proposal.binding)["includeServer"])
            val server = requireNotNull(context.proposal.binding.compact).server
            assertEquals(if (mode == RouterStorageMode.FLASH) "hysteria" else "hysteria-server-ram", requireNotNull(server).compactText("name"))
        }
    }

    @Test fun compactProofCannotAuthorizeMissingOrDifferentModulesEvenWithARecomputedBudgetHash() = runTest {
        val context = compactContext(RouterStorageMode.RAM)
        val plan = requireNotNull(context.payload.compact).modes.getValue(RouterStorageMode.RAM)
        val modules = plan.binding.modules.take(1)
        val changed = rebound(context, plan.document.compactChanged("modules", JsonArray(modules.map(::compactClientJson))), modules)
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(changed), changed.payload, changed.proposal) }
        val original = compactProof(context)
        for (field in listOf("producerSha256", "capabilitiesSha256", "manifestSha256", "packageSha256", "legalProvenanceSha256", "binarySha256")) {
            val entries = plan.binding.modules.mapIndexed { index, module ->
                if (index == 0) module.copy(entry = module.entry.compactChanged(field, JsonPrimitive("0".repeat(64)))) else module
            }
            val badEntry = compactDocument(context.proposal.binding).compactChanged("modules", JsonArray(entries.map(::compactClientJson)))
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context, badEntry), context.payload, context.proposal) }
        }
        verifyLabRouterBundleBinding(original, context.payload, context.proposal)
    }

    @Test fun compactServerCannotBeChangedOrAddedWithoutItsExactReleasedRole() = runTest {
        val context = compactContext(RouterStorageMode.RAM, server = true)
        val plan = requireNotNull(context.payload.compact).modes.getValue(RouterStorageMode.RAM)
        val changedServer = requireNotNull(plan.binding.server).compactChanged("sha256", JsonPrimitive("0".repeat(64)))
        val changed = rebound(context, plan.document.compactChanged("server", changedServer), server = changedServer)
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(changed), changed.payload, changed.proposal) }
        val without = compactContext(RouterStorageMode.RAM)
        val unexpected = compactDocument(without.proposal.binding).compactChanged("server", changedServer)
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(without, unexpected), without.payload, without.proposal) }
        val missing = compactDocument(context.proposal.binding).compactChanged("server", JsonNull)
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context, missing), context.payload, context.proposal) }
    }

    @Test fun compactModeAndBudgetMustBindTheActualSelectedDocument() = runTest {
        val context = compactContext(RouterStorageMode.RAM)
        val current = requireNotNull(context.payload.compact)
        val plan = current.modes.getValue(RouterStorageMode.RAM)
        val changedDocument = plan.document.compactChanged("requiredRamBytes", JsonPrimitive(1))
        val unbound = context.payload.copy(compact = RouterCompactPayload(current.modes + (RouterStorageMode.RAM to plan.copy(document = changedDocument))))
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context), unbound, context.proposal) }
        val wrongMode = rebound(context, plan.document.compactChanged("mode", JsonPrimitive("flash")))
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(wrongMode), wrongMode.payload, wrongMode.proposal) }
        val otherMode = context.proposal.binding.copy(storageMode = RouterStorageMode.FLASH)
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context), context.payload, compactProposal(context.fixture, otherMode)) }
    }

    @Test fun compactCatalogPlatformTargetAndHashesCannotReuseAnotherAcceptedProof() = runTest {
        val context = compactContext(RouterStorageMode.RAM)
        val selected = context.proposal.binding
        for (binding in listOf(selected.copy(selection = selected.selection.copy(catalogSha256 = "0".repeat(64))),
            selected.copy(variantId = "other"), selected.copy(target = "armsr/armv8"),
            selected.copy(budgetSha256 = "0".repeat(64)), selected.copy(coreProducerSha256 = "0".repeat(64)))) {
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context), context.payload, compactProposal(context.fixture, binding)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyLabRouterBundleBinding(compactProof(context), context.payload, compactProposal(context.fixture, selected, "25.12.4"))
        }
        val entry = compactDocument(selected)
        for ((key, value) in listOf("engineCatalogSha256" to "0".repeat(64), "packageUniverseSha256" to "0".repeat(64), "mode" to "flash")) {
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context, entry.compactChanged(key, JsonPrimitive(value))), context.payload, context.proposal) }
        }
    }

    @Test fun compactAndLegacyPayloadsCannotDowngradeOrMixTheirProofShapes() = runTest {
        val context = compactContext(RouterStorageMode.RAM)
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context), context.payload.copy(variant = variant), context.proposal) }
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context), context.payload.copy(compact = null), context.proposal) }
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context), context.payload, compactProposal(context.fixture, binding)) }
        assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context, bindingDocument()), context.payload, context.proposal) }
        for ((key, value) in listOf("schema" to JsonPrimitive(1), "kind" to JsonPrimitive("legacy"))) {
            val entry = compactDocument(context.proposal.binding).compactChanged(key, value)
            assertThrows(IllegalArgumentException::class.java) { verifyLabRouterBundleBinding(compactProof(context, entry), context.payload, context.proposal) }
        }
    }
}
