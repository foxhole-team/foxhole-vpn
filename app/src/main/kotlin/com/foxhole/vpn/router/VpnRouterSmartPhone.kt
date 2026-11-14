package com.foxhole.vpn.router

import com.foxhole.vpn.DefaultVpnController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class VpnRouterSmartPhone(private val controller: DefaultVpnController) : RouterSmartPhone {
    private class Owned(val pause: RouterSmartPause, val receipt: RouterPausedPhone) {
        @Volatile var startTicket: Long? = null
    }
    @Volatile private var owned: Owned? = null
    override val changes = combine(controller.state.map { Triple(it.selectedProfileId, it.profileRevision, it.phase) }.distinctUntilChanged(),
        controller.routerManualGeneration) { _, _ -> Unit }
    override fun manualGeneration(): Long = controller.routerManualGeneration.value
    override fun isIdle(): Boolean = controller.routerPhoneSnapshot().idle
    override fun isCurrent(paused: RouterSmartPause): Boolean {
        val value = owned?.takeIf { it.pause == paused } ?: return false
        return value.startTicket?.let { controller.routerSmartStartPending(it) }
            ?: controller.routerSmartPausedCurrent(value.receipt)
    }
    override fun pause(profile: RouterConnectedProfile, local: RouterWifiConnection, verifiedAt: Long,
        authorityRevision: Long, pairingId: String): RouterSmartPause? {
        if (controller.routerConnectedProfile() != profile) return null
        val owner = controller.routerPhoneSnapshot().connected ?: return null
        val receipt = controller.pausePhoneForRouter(RouterAutomaticPause(owner, pairingId)) ?: return null
        return RouterSmartPause(profile, pairingId, java.util.UUID.randomUUID().toString()).also { owned = Owned(it, receipt) }
    }
    override suspend fun resume(paused: RouterSmartPause, authorityRevision: Long, stillAllowed: () -> Boolean): Boolean {
        val value = owned?.takeIf { it.pause == paused } ?: return false
        if (!stillAllowed()) return false
        return controller.resumePhoneForRouter(value.receipt, smartAuthority = stillAllowed,
            smartReserved = { value.startTicket = it.connectionId }) != null
    }
    override fun forget(paused: RouterSmartPause) {
        val value = owned?.takeIf { it.pause == paused } ?: return
        value.startTicket?.let(controller::cancelRouterSmartStart)
        controller.forgetRouterPause(value.receipt)
        if (owned === value) owned = null
    }
}
