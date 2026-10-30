package com.foxhole.vpn.ui

import com.foxhole.vpn.VpnPhase

internal enum class GlobeCallout { NONE, DEVICE, SERVER }

internal data class GlobeCalloutCue(
    val introPending: Boolean = true,
    val deviceExpanded: Boolean = false,
    val serverDismissed: Boolean = false,
    val serverConnectionId: Long? = null,
    val confirmationActive: Boolean = false,
) {
    fun visible(confirmed: Boolean): GlobeCallout = when {
        !confirmed && confirmationActive -> GlobeCallout.NONE
        confirmed && deviceExpanded -> GlobeCallout.DEVICE
        confirmed -> if (serverDismissed) GlobeCallout.NONE else GlobeCallout.SERVER
        deviceExpanded -> GlobeCallout.DEVICE
        else -> GlobeCallout.NONE
    }

    fun locationReady(confirmed: Boolean, hasDevice: Boolean): GlobeCalloutCue = when {
        confirmed -> if (introPending) dismissDevice() else this
        introPending && hasDevice -> copy(introPending = false, deviceExpanded = true)
        else -> this
    }

    fun phaseChanged(phase: VpnPhase, returnedHome: Boolean, hasDevice: Boolean): GlobeCalloutCue = when {
        phase == VpnPhase.IDLE && returnedHome -> copy(
            introPending = !hasDevice, deviceExpanded = hasDevice, serverDismissed = false,
            serverConnectionId = null, confirmationActive = false,
        )
        phase == VpnPhase.CONNECTING || phase == VpnPhase.VALIDATING || phase == VpnPhase.STOPPING -> dismissDevice()
        else -> this
    }

    fun touchStarted(): GlobeCalloutCue = copy(introPending = false)
    fun dismissDevice(): GlobeCalloutCue = copy(introPending = false, deviceExpanded = false)
    fun toggleDevice(confirmed: Boolean): GlobeCalloutCue =
        copy(introPending = false, deviceExpanded = !deviceExpanded, serverDismissed = serverDismissed || confirmed)

    fun showDevice(confirmed: Boolean): GlobeCalloutCue =
        copy(introPending = false, deviceExpanded = true, serverDismissed = serverDismissed || confirmed)

    fun dismissServer(): GlobeCalloutCue = dismissDevice().copy(serverDismissed = true)
    fun showServer(confirmed: Boolean): GlobeCalloutCue = if (confirmed) dismissDevice().copy(serverDismissed = false) else this
    fun recenter(confirmed: Boolean): GlobeCalloutCue = if (confirmed) dismissServer() else dismissDevice()

    fun connectionChanged(confirmed: Boolean, connectionId: Long?): GlobeCalloutCue {
        if (!confirmed) return copy(confirmationActive = false, deviceExpanded = if (confirmationActive) false else deviceExpanded)
        val newConnection = if (connectionId != null) serverConnectionId != connectionId else !confirmationActive || serverConnectionId != null
        return copy(introPending = false, deviceExpanded = if (newConnection) false else deviceExpanded, serverDismissed = if (newConnection) false else serverDismissed,
            serverConnectionId = connectionId, confirmationActive = true)
    }

    fun save(processId: String): List<Any?> =
        listOf(processId, 1, introPending, deviceExpanded, serverDismissed, serverConnectionId, confirmationActive)

    companion object {
        fun restore(saved: Any?, processId: String): GlobeCalloutCue {
            val fields = saved as? List<*> ?: return GlobeCalloutCue()
            if (fields.size != 7 || fields[0] != processId || fields[1] != 1) return GlobeCalloutCue()
            return GlobeCalloutCue(
                introPending = fields[2] as? Boolean ?: return GlobeCalloutCue(),
                deviceExpanded = fields[3] as? Boolean ?: return GlobeCalloutCue(),
                serverDismissed = fields[4] as? Boolean ?: return GlobeCalloutCue(),
                serverConnectionId = when (val ticket = fields[5]) { null -> null; is Long -> ticket; else -> return GlobeCalloutCue() },
                confirmationActive = fields[6] as? Boolean ?: return GlobeCalloutCue(),
            )
        }

        fun restore(sameProcess: Boolean, introPending: Boolean, deviceExpanded: Boolean): GlobeCalloutCue =
            if (sameProcess) GlobeCalloutCue(introPending, deviceExpanded) else GlobeCalloutCue()
    }
}

internal fun placeGlobeCallout(rotated: FloatArray, persistentServer: Boolean, output: FloatArray) {
    val rearDepth = if (persistentServer) (-rotated[2]).coerceIn(0f, 1f) else 0f
    output[0] = rotated[0] * (1f - rearDepth)
    output[1] = rotated[1] * (1f - rearDepth) + rearDepth
}
