package com.foxhole.vpn.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.camera.CameraInstance
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrScanLifecycleAndroidTest {
    @Test fun aGrantedCameraClosesOnStopAndRecreationAndBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Root must explicitly grant CAMERA for the hardware acceptance stage",
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Camera hardware or an emulated camera is required", cameraCount() > 0)
        ActivityScenario.launch<QrScanActivity>(Intent(context, QrScanActivity::class.java)).use { scenario ->
            val first = awaitPreview(scenario)
            val firstCamera = requireNotNull(first.cameraInstance)
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitClosed(firstCamera)
            scenario.moveToState(Lifecycle.State.RESUMED)
            val second = awaitPreview(scenario)
            assertNotSame(first, second)
            val secondCamera = requireNotNull(second.cameraInstance)
            scenario.recreate()
            awaitClosed(secondCamera)
            val third = awaitPreview(scenario)
            val thirdCamera = requireNotNull(third.cameraInstance)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitClosed(thirdCamera)
        }
    }

    private fun awaitPreview(scenario: ActivityScenario<QrScanActivity>): BarcodeView {
        var result: BarcodeView? = null
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (result == null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity -> result = findPreview(activity.window.decorView)?.takeIf { it.isPreviewActive } }
            if (result == null) SystemClock.sleep(20)
        }
        return requireNotNull(result) { "Camera preview did not become active" }
    }

    private fun awaitClosed(camera: CameraInstance) {
        var closed = false
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { closed = camera.isCameraClosed }
            if (!closed) SystemClock.sleep(20)
        }
        assertTrue("Camera must close after leaving the foreground", closed)
    }

    private fun findPreview(view: View): BarcodeView? {
        if (view is BarcodeView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPreview(view.getChildAt(index))?.let { return it }
        return null
    }

    @Suppress("DEPRECATION")
    private fun cameraCount(): Int = Camera.getNumberOfCameras()
}
