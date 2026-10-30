package com.foxhole.vpn.qr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.ui.LocalVpnColors
import com.foxhole.vpn.ui.VpnModalTheme
import com.foxhole.vpn.ui.VpnUiTheme
import com.foxhole.vpn.ui.VpnNoticeScope
import com.foxhole.vpn.ui.VpnNoticeState
import com.foxhole.vpn.ui.VpnNoticeWindow
import com.foxhole.vpn.ui.LocalVpnNotices
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview

class QrScanActivity : AppCompatActivity() {
    private var session = QrScanSession()
    private var scanState by mutableStateOf(session.snapshot())
    private var preview: BarcodeView? = null
    private var notices: VpnNoticeState? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.permissionResult(granted && hasCameraPermission())
        publishState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = QrScanSession(savedInstanceState?.getBoolean(PERMISSION_REQUESTED) ?: false)
        scanState = session.snapshot()
        // Camera QR contents must not become a recents snapshot.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelScan()
        })
        setContent {
            val settings by (application as VpnApplication).controller.settings.collectAsStateWithLifecycle()
            VpnUiTheme(settings.theme, settings.fontStyle) {
                val colors = LocalVpnColors.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = colors.light
                        isAppearanceLightNavigationBars = colors.light
                    }
                }
                VpnNoticeScope {
                    val noticeState = LocalVpnNotices.current
                    SideEffect { notices = noticeState }
                    VpnNoticeWindow()
                    VpnModalTheme {
                        QrScanContent(scanState, ::requestCameraPermission, ::openAppSettings, ::retryCamera, ::cancelScan) { token ->
                            key(token) {
                                AndroidView(
                                    factory = { context -> BarcodeView(context).also { attachPreview(it, token) } },
                                    modifier = Modifier.fillMaxSize(),
                                    onReset = null,
                                    onRelease = ::releasePreview,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        session.setResumed(true)
        session.setPermission(hasCameraPermission())
        publishState()
        if (session.shouldRequestOnOpen) requestCameraPermission()
    }

    override fun onPause() {
        session.setResumed(false)
        pausePreview()
        publishState()
        super.onPause()
    }

    override fun onStop() {
        session.setResumed(false)
        pausePreview()
        super.onStop()
    }

    override fun onDestroy() {
        pausePreview()
        notices = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PERMISSION_REQUESTED, session.permissionRequested)
        super.onSaveInstanceState(outState)
    }

    private fun requestCameraPermission() {
        session.setPermission(hasCameraPermission())
        if (session.requestPermission()) {
            publishState()
            try { cameraPermission.launch(Manifest.permission.CAMERA) }
            catch (_: RuntimeException) { session.permissionResult(false); publishState() }
        } else publishState()
    }

    private fun retryCamera() {
        session.setPermission(hasCameraPermission())
        session.retryCamera()
        publishState()
    }

    private fun attachPreview(view: BarcodeView, token: Long) {
        view.isSaveEnabled = false
        view.keepScreenOn = true
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.decoderFactory = qrOnlyDecoderFactory()
        view.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) { failCamera(view, token) }
        })
        view.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (result?.barcodeFormat != BarcodeFormat.QR_CODE || isFinishing) return
                if (!hasCameraPermission()) {
                    session.setPermission(false)
                    pausePreview()
                    publishState()
                    return
                }
                val text = session.claim(token, result.text) ?: return
                pausePreview()
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PROFILE_TEXT, text))
                finish()
            }
        })
        preview = view
        view.post {
            if (preview === view && session.isCurrent(token) && !isFinishing) {
                if (hasCameraPermission()) {
                    try { view.resume() } catch (_: RuntimeException) { failCamera(view, token) }
                } else {
                    session.setPermission(false)
                    releasePreview(view)
                    publishState()
                }
            }
        }
    }

    private fun failCamera(view: BarcodeView, token: Long) {
        if (preview === view && session.cameraFailed(token)) {
            releasePreview(view)
            publishState()
        }
    }

    private fun releasePreview(view: BarcodeView) {
        if (preview === view) preview = null
        view.keepScreenOn = false
        view.stopDecoding()
        view.pause()
    }

    private fun pausePreview() { preview?.let(::releasePreview) }
    private fun publishState() { scanState = session.snapshot() }
    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        catch (_: RuntimeException) { notices?.show(getString(R.string.vpn_qr_settings_unavailable), error = true) }
    }

    private fun cancelScan() {
        if (isFinishing) return
        session.cancel()
        pausePreview()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_PROFILE_TEXT = "com.foxhole.vpn.qr.PROFILE_TEXT"
        private const val PERMISSION_REQUESTED = "camera_permission_requested"
    }
}
