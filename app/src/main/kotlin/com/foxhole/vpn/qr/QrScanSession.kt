package com.foxhole.vpn.qr

internal data class QrScanState(
    val permissionGranted: Boolean = false,
    val permissionPending: Boolean = false,
    val cameraUnavailable: Boolean = false,
    val token: Long? = null,
)

internal class QrScanSession(permissionRequested: Boolean = false) {
    var permissionRequested = permissionRequested
        private set
    private var permissionPending = false
    private var permissionGranted = false
    private var resumed = false
    private var failed = false
    private var delivered = false
    private var cancelled = false
    private var generation = 0L

    val canScan: Boolean get() = resumed && permissionGranted && !permissionPending && !failed && !delivered && !cancelled
    val token: Long? get() = generation.takeIf { canScan }
    val shouldRequestOnOpen: Boolean get() = resumed && !permissionGranted && !permissionRequested && !permissionPending && !delivered && !cancelled

    fun setResumed(value: Boolean) {
        if (resumed != value) { resumed = value; generation++ }
    }

    fun setPermission(granted: Boolean) {
        if (permissionGranted != granted) {
            permissionGranted = granted
            if (granted) failed = false
            generation++
        }
    }

    fun requestPermission(): Boolean {
        if (!resumed || permissionGranted || permissionPending || delivered || cancelled) return false
        permissionRequested = true
        permissionPending = true
        generation++
        return true
    }

    fun permissionResult(granted: Boolean) {
        if (permissionPending) { permissionPending = false; generation++ }
        setPermission(granted)
    }

    fun cameraFailed(token: Long): Boolean {
        if (!isCurrent(token)) return false
        failed = true
        generation++
        return true
    }

    fun retryCamera() {
        if (failed && !delivered && !cancelled) { failed = false; generation++ }
    }

    fun isCurrent(token: Long): Boolean = canScan && token == generation

    fun claim(token: Long, raw: String?): String? {
        if (!isCurrent(token) || raw == null || raw.length > MAX_QR_BYTES) return null
        val text = raw.trim()
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_QR_BYTES) return null
        delivered = true
        generation++
        return text
    }

    fun cancel() { cancelled = true; generation++ }

    fun snapshot(): QrScanState = QrScanState(permissionGranted, permissionPending, failed, token)

    private companion object { const val MAX_QR_BYTES = 16 * 1024 }
}
