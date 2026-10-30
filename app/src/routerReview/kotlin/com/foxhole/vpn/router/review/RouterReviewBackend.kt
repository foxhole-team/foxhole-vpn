package com.foxhole.vpn.router.review

import com.foxhole.vpn.router.RouterControlCoordinator
import com.foxhole.vpn.router.RouterControlPreferences
import com.foxhole.vpn.router.RouterEndpoint
import com.foxhole.vpn.router.RouterHttpProvisioner
import com.foxhole.vpn.router.RouterInstallBinding
import com.foxhole.vpn.router.RouterInstallBudget
import com.foxhole.vpn.router.RouterInstallProposal
import com.foxhole.vpn.router.RouterInstallProtocolSelection
import com.foxhole.vpn.router.RouterInstallResult
import com.foxhole.vpn.router.RouterInstallProgress
import com.foxhole.vpn.router.RouterInstallProgressStage
import com.foxhole.vpn.router.RouterPanelApi
import com.foxhole.vpn.router.RouterPanelSession
import com.foxhole.vpn.router.RouterPairing
import com.foxhole.vpn.router.RouterProfileSelection
import com.foxhole.vpn.router.RouterResourceFacts
import com.foxhole.vpn.router.RouterSetupFailure
import com.foxhole.vpn.router.RouterSetupService
import com.foxhole.vpn.router.RouterStorageMode
import com.foxhole.vpn.router.RouterTlsIdentity
import com.foxhole.vpn.router.RouterWizardCoordinator
import com.foxhole.vpn.router.RouterWizardError
import com.foxhole.vpn.router.RouterWizardOptions
import com.foxhole.vpn.router.RouterUpdateStatus
import com.foxhole.vpn.router.RouterWizardProvisioner
import com.foxhole.vpn.router.RouterWizardResult
import com.foxhole.vpn.router.RouterWizardTransport
import com.foxhole.vpn.router.chooseRouterStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** Offline review variant only; never constructs Wi-Fi, SSH, sockets, or persistent stores. */
internal class RouterReviewBackend(
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.Default,
    private val stageDelayMs: Long = 250,
    initiallyInstalled: Boolean = true,
) : AutoCloseable {
    private val ownedScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + io)
    private val panels = List(3) { RouterReviewPanel(initiallyInstalled) }
    private val callSets = panels.map { RouterReviewCalls(ownedScope, it, stageDelayMs) }
    private val identities = List(3) { RouterTlsIdentity(RouterEndpoint("192.168.1.1"), reviewDigest("offline-demo-certificate${if (it == 0) "" else "-$it"}")) }
    private val hostKeys = List(3) { "offline-demo-ssh-host-key${if (it == 0) "" else "-$it"}".toByteArray() }
    @Volatile private var currentRouter = 0
    private val unreachable = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val panel get() = panels[currentRouter]
    private val calls get() = callSets[currentRouter]
    private val identity get() = identities[currentRouter]
    private val hostKey get() = hostKeys[currentRouter]
    private val wizardInstances = mutableListOf<RouterWizardCoordinator>()
    private val controlInstances = mutableListOf<RouterControlCoordinator>()
    @Volatile private var resourceChoice = RouterStorageMode.FLASH
    @Volatile private var homeActive = false

    init {
        require(stageDelayMs in 0..1000)
    }

    val pairingStore = RouterReviewPairingStore { selected -> identities.indexOf(selected).takeIf { it >= 0 }?.let { currentRouter = it } }
    val preferenceStore = RouterReviewPreferenceStore(pairingStore)
    val installationCount: Int get() = panel.installationCount
    val provisioner = RouterHttpProvisioner(
        localAddress = { "192.168.1.101" },
        io = io,
        elapsedMs = { System.nanoTime() / 1_000_000 },
        pairingStore = pairingStore,
    )
    val transport: RouterWizardTransport = object : RouterWizardTransport {
        override fun gateway(): String = "192.168.1.1"

        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
            checkAddress(endpoint.address)
            delay(stageDelayMs)
            if (!panel.installed || currentRouter in unreachable) throw java.io.IOException("demo_router_not_installed")
            return identity
        }

        override fun api(identity: RouterTlsIdentity): RouterPanelApi {
            require(identity == this@RouterReviewBackend.identity)
            return RouterPanelApi(identity, calls)
        }
        override fun beginAddition() { currentRouter = pairingStore.size.coerceAtMost(2) }
    }
    val setupService: RouterSetupService = object : RouterSetupService {
        override val installCatalog = routerReviewCatalog()

        override suspend fun inspectSshIdentity(address: String): ByteArray {
            checkAddress(address)
            if (currentRouter in unreachable) throw java.io.IOException("demo_router_unreachable")
            delay(stageDelayMs)
            return hostKey.copyOf()
        }

        override suspend fun inspect(
            address: String,
            hostKey: ByteArray,
            password: CharArray,
            selection: RouterInstallProtocolSelection,
        ): RouterInstallProposal {
            checkAddress(address)
            if (!hostKey.contentEquals(this@RouterReviewBackend.hostKey)) {
                throw RouterSetupFailure(RouterWizardError.IDENTITY)
            }
            if (password.isEmpty() || password.all(Char::isWhitespace)) {
                throw RouterSetupFailure(RouterWizardError.PASSWORD)
            }
            delay(stageDelayMs * 2)
            val variant = installCatalog.variants.singleOrNull { it.protocols == selection.protocols }
                ?: throw RouterSetupFailure(RouterWizardError.UNSUPPORTED)
            require(selection.catalogSha256 == installCatalog.sha256)
            val mode = chooseRouterStorage(resourceFacts(), DEMO_BUDGET)
                ?: throw RouterSetupFailure(RouterWizardError.STORAGE)
            val binding = RouterInstallBinding(
                selection,
                variant.id,
                variant.architecture,
                mode,
                variant.coreProducerSha256,
                variant.coreBinarySha256,
                variant.coreSourceSha256,
                variant.capabilitiesSha256,
                variant.modes.getValue(mode).sha256,
                "x86/64",
            )
            return RouterInstallProposal(
                "25.12.5",
                variant.architecture,
                mode,
                installCatalog.manifestSha256,
                ReviewInstallTransaction(),
                binding,
            )
        }

        override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult {
            val transaction = proposal.transaction as? ReviewInstallTransaction
                ?: throw RouterSetupFailure(RouterWizardError.INSTALL)
            require(installCatalog.matches(proposal.binding, proposal.openWrtVersion, proposal.manifestSha256))
            transaction.begin()
            delay(stageDelayMs * 5)
            currentCoroutineContext().ensureActive()
            transaction.ensureOpen()
            panel.install(proposal.binding.selection.protocols)
            return RouterInstallResult(identity, "1234".toCharArray())
        }

        override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
            selection: RouterInstallProtocolSelection, onProgress: (RouterInstallProgress) -> Unit): RouterInstallProposal {
            val proposal = inspect(address, hostKey, password, selection)
            try {
                simulateProgress(RouterInstallProgressStage.DOWNLOAD, onProgress)
                return proposal
            } catch (failure: Exception) { proposal.close(); throw failure }
        }

        override suspend fun install(proposal: RouterInstallProposal,
            onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult {
            simulateProgress(RouterInstallProgressStage.UPLOAD, onProgress)
            simulateProgress(RouterInstallProgressStage.INSTALL, onProgress)
            val result = install(proposal)
            try {
                simulateProgress(RouterInstallProgressStage.CHECK, onProgress)
                return result
            } catch (failure: Exception) { result.close(); throw failure }
        }
    }
    private val wizardProvisioner = object : RouterWizardProvisioner {
        override val supportedInboundProtocols = setOf("hysteria2")
        override val supportsHomeAutodetection = true

        override suspend fun availableInboundProtocols(api: RouterPanelApi, session: RouterPanelSession): Set<String> =
            provisioner.availableInboundProtocols(api, session)

        override suspend fun routerCountry(api: RouterPanelApi, session: RouterPanelSession): String? =
            provisioner.routerCountry(api, session)

        override suspend fun updateStatus(api: RouterPanelApi, session: RouterPanelSession): RouterUpdateStatus =
            provisioner.updateStatus(api, session)

        override suspend fun setUpdateAuto(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus,
            enabled: Boolean): RouterUpdateStatus = provisioner.setUpdateAuto(api, session, status, enabled)

        override suspend fun finish(
            api: RouterPanelApi,
            session: RouterPanelSession,
            pairing: RouterPairing,
            options: RouterWizardOptions,
            selectedProfile: RouterProfileSelection?,
        ): RouterWizardResult {
            val result = provisioner.finish(
                api,
                session,
                pairing,
                options.copy(homeAutodetection = false),
                selectedProfile?.let(::literalDemoSelection),
            )
            currentCoroutineContext().ensureActive()
            preferenceStore.save(
                RouterControlPreferences(
                    pairing.id,
                    enabled = true,
                    countryCode = options.countryCode,
                    countryAuto = options.countryAuto,
                    countrySitesDirect = options.countrySitesDirect,
                    homeAutodetection = options.homeAutodetection,
                    publicIpConfirmed = options.publicIp,
                ),
            )
            simulateHome(options.homeAutodetection)
            return result
        }
    }

    fun wizard(
        deviceName: String,
        exportProfile: suspend (String) -> RouterProfileSelection?,
        importRemoteProfile: suspend (String) -> Boolean,
    ): RouterWizardCoordinator = RouterWizardCoordinator(
        transport,
        pairingStore,
        deviceName,
        setupService,
        wizardProvisioner,
        exportProfile,
        importRemoteProfile,
        preferenceStore = preferenceStore,
        beforeRouterSelection = { homeActive = false },
    ).also { wizardInstances += it }

    fun controls(copyProfile: suspend (String) -> Unit): RouterControlCoordinator = controls(copyProfile, { false })

    fun controls(
        copyProfile: suspend (String) -> Unit,
        importProfile: suspend (String) -> Boolean,
    ): RouterControlCoordinator = RouterControlCoordinator(
        transport,
        pairingStore,
        preferenceStore,
        provisioner,
        homeAutomationAvailable = true,
        applyHomeAutomation = { enabled ->
            val pairing = pairingStore.read() ?: throw RouterSetupFailure(RouterWizardError.UNAVAILABLE)
            try {
                val old = preferenceStore.read(pairing.id)
                preferenceStore.save(old.copy(homeAutodetection = enabled))
                simulateHome(enabled)
            } finally {
                pairing.clear()
            }
        },
        homeAutomationActive = { homeActive },
        copyProfile = copyProfile,
        importProfile = importProfile,
        beforeRouterSelection = { homeActive = false },
    ).also { controlInstances += it }

    fun resourceMode(mode: RouterStorageMode) {
        resourceChoice = mode
    }
    fun routerReachable(number: Int, reachable: Boolean) {
        require(number in 1..3)
        if (reachable) unreachable.remove(number - 1) else unreachable.add(number - 1)
    }

    fun incomingConfigured(configured: Boolean) {
        panel.incomingConfigured = configured
    }

    suspend fun simulateHome(active: Boolean) {
        currentCoroutineContext().ensureActive()
        homeActive = active
        controlInstances.forEach { it.homeMonitoringChanged(active) }
    }

    private fun resourceFacts(): RouterResourceFacts = RouterResourceFacts(
        availableFlashBytes = if (resourceChoice == RouterStorageMode.FLASH) 64 * MIB else 16 * MIB,
        availableRamBytes = 512 * MIB,
    )

    private suspend fun simulateProgress(stage: RouterInstallProgressStage, onProgress: (RouterInstallProgress) -> Unit) {
        if (stage == RouterInstallProgressStage.INSTALL || stage == RouterInstallProgressStage.CHECK) {
            onProgress(RouterInstallProgress(stage))
            delay(stageDelayMs * 5)
            return
        }
        for (percent in 0..100 step 25) {
            currentCoroutineContext().ensureActive()
            onProgress(RouterInstallProgress(stage, percent.toLong(), 100))
            delay(stageDelayMs)
        }
    }

    // The real provisioner's parser resolves domain names. Feed only numeric synthetic fixtures
    // in this offline variant, including numeric SNI; no supplied profile content is transmitted.
    internal fun literalDemoSelection(selected: RouterProfileSelection): RouterProfileSelection {
        val configuration = when (selected.selectedConfiguration.trim()) {
            DEMO_PROFILE_URI, PHONE_DEMO_HY2_URI ->
                DEMO_PROFILE_URI.takeIf { selected.selectedProtocol == "hysteria2" }
            DEMO_VLESS_URI, PHONE_DEMO_VLESS_URI ->
                DEMO_VLESS_URI.takeIf { selected.selectedProtocol == "vless" }
            else -> null
        } ?: throw RouterSetupFailure(RouterWizardError.PROFILE)
        if (selected.sourceUrl != null) {
            throw RouterSetupFailure(RouterWizardError.PROFILE)
        }
        return RouterProfileSelection(
            selected.phoneProfileId,
            selected.displayName,
            selected.selectedProtocol,
            selected.selectedNodeName,
            selected.selectedOptionId,
            null,
            configuration,
        )
    }

    override fun close() {
        wizardInstances.forEach { it.close() }
        controlInstances.forEach { it.close() }
        ownedScope.cancel()
        pairingStore.close()
        panels.forEach { it.close() }
        hostKeys.forEach { it.fill(0) }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val PHONE_DEMO_HY2_URI =
            "hysteria2://DemoOnly@demo-router.invalid:443/?sni=demo-router.invalid#Demo%20HY2"
        const val PHONE_DEMO_VLESS_URI =
            "vless://11111111-1111-4111-8111-111111111111@demo-node.invalid:443" +
                "?encryption=none&security=tls&type=grpc&serviceName=Demo&mode=gun#Demo%20gRPC"
        val DEMO_BUDGET = RouterInstallBudget(32 * MIB, 8 * MIB, 128 * MIB, 256 * MIB)

        fun checkAddress(address: String) {
            if (address != "192.168.1.1") throw RouterSetupFailure(RouterWizardError.ADDRESS)
        }
    }
}

private class ReviewInstallTransaction : AutoCloseable {
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    fun begin() {
        ensureOpen()
        check(started.compareAndSet(false, true))
    }

    fun ensureOpen() {
        check(!closed.get())
    }

    override fun close() {
        closed.set(true)
    }
}
