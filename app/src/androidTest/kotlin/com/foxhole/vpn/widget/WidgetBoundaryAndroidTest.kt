package com.foxhole.vpn.widget

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.MainActivity
import com.foxhole.vpn.VpnApplication
import com.foxhole.vpn.R
import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnTheme
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.VpnProfileSummary
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class WidgetBoundaryAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun widgetCommandsAreImmutablePrivateAndBoundToTheShownProfile() {
        val license = (context.applicationContext as VpnApplication).licenseConsent
        runBlocking { license.load() }
        val accepted = license.state.value.accepted
        val intent = widgetCommandIntent(context, 88_041, WidgetAction.START, "saved-profile")
        assertEquals(ComponentName(context, WidgetActionReceiver::class.java), intent.component)
        assertEquals(WidgetCommand(WidgetAction.START, "saved-profile"), widgetCommand(context, intent))
        assertNull(widgetCommand(context, Intent(intent).setClass(context, MainActivity::class.java)))
        assertNull(widgetCommand(context, Intent(intent).setAction(Intent.ACTION_VIEW)))
        val pending = widgetCommandPendingIntent(context, 88_041, WidgetAction.START, "saved-profile")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(pending.isImmutable)
                assertEquals(accepted, pending.isBroadcast)
                assertEquals(!accepted, pending.isActivity)
            }
            assertEquals(context.packageName, pending.creatorPackage)
        } finally {
            pending.cancel()
        }
    }

    @Test
    fun onlyMissingStartPermissionUsesThePrivateConsentActivity() {
        val intent = widgetPermissionIntent(context, 88_043, WidgetAction.START, "shown-profile")
        assertEquals(ComponentName(context, WidgetActionActivity::class.java), intent.component)
        assertEquals(WidgetCommand(WidgetAction.START, "shown-profile"), widgetPermissionCommand(context, intent))
        assertNull(widgetCommand(context, intent))
        val start = widgetCommandPendingIntent(context, 88_043, WidgetAction.START, "shown-profile", permissionRequired = true)
        val stop = widgetCommandPendingIntent(context, 88_044, WidgetAction.STOP, "shown-profile", permissionRequired = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(start.isActivity)
                assertTrue(start.isImmutable)
                assertTrue(stop.isBroadcast)
                assertFalse(stop.isActivity)
                assertTrue(stop.isImmutable)
            }
        } finally {
            start.cancel()
            stop.cancel()
        }
    }

    @Test
    fun widgetEntriesArePrivateAndAnotherUidCannotLaunchConsentActivity() {
        val component = ComponentName(context, WidgetActionActivity::class.java)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
        listOf(StatusWidgetReceiver::class.java, FoxStatusWidgetReceiver::class.java, WidgetActionReceiver::class.java).forEach { receiver ->
            assertFalse(context.packageManager.getReceiverInfo(ComponentName(context, receiver), 0).exported)
        }
        assertEquals("2000", shellOutput("id -u").trim())
        assertTrue(context.applicationInfo.uid != 2000)
        val action = checkNotNull(widgetCommandIntent(context, 88_042, WidgetAction.START, null).action)
        val output = shellOutput("am start -W -n ${component.flattenToString()} -a $action")
        assertTrue(output, output.contains("Permission Denial") || output.contains("not exported"))
    }

    @Test
    fun statusWidgetShowsPhoneFlagAndCurrentCityInBothConnectionStates() {
        instrumentation.runOnMainSync {
            val profile = VpnProfileSummary("widget-profile", "Profile name should not replace location", "VLESS")
            val home = VpnLocation("DE", "Berlin", "Germany", "192.0.2.1")
            val disconnected = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id,
                deviceLocation = VpnDeviceLocation(home, DeviceLocationStatus.CURRENT, 123L))
            val connected = disconnected.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true,
                location = VpnLocation("NL", "Amsterdam", "Netherlands", "192.0.2.2"))
            listOf(false, true).forEach { compact ->
                listOf(disconnected to "Berlin", connected to "Amsterdam").forEach { (state, city) ->
                    val view = statusWidgetViews(context, state, VpnSettings(language = VpnLanguage.EN), 88_045, compact)
                        .apply(context, FrameLayout(context))
                    assertEquals(city, view.findViewById<TextView>(R.id.widget_status_detail).text.toString())
                    assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_status_device).visibility)
                    val flag = view.findViewById<ImageView>(R.id.widget_status_flag)
                    assertEquals(View.VISIBLE, flag.visibility)
                    assertTrue(flag.drawable != null)
                    assertFalse(view.findViewById<View>(R.id.widget_status_root).hasOnClickListeners())
                    assertTrue(view.findViewById<View>(R.id.widget_status_logo_touch).hasOnClickListeners())
                    assertTrue(view.findViewById<View>(R.id.widget_status_power_touch).hasOnClickListeners())
                }
            }
        }
    }

    @Test
    fun cachedCityIsMarkedAndRetainedTunnelErrorsRemoveTheOldFlag() {
        instrumentation.runOnMainSync {
            val location = VpnLocation("DE", "Berlin", "Germany", "192.0.2.1")
            val cached = VpnUiState(isReady = true, deviceLocation = VpnDeviceLocation(location, DeviceLocationStatus.CACHED, 123L))
            val settings = VpnSettings(language = VpnLanguage.EN)
            val cachedView = statusWidgetViews(context, cached, settings, 88_046, compact = true).apply(context, FrameLayout(context))
            assertEquals("Berlin", cachedView.findViewById<TextView>(R.id.widget_status_detail).text.toString())
            val failed = cached.copy(phase = VpnPhase.ERROR, hasOwnedTunnel = true, location = location)
            val failedView = statusWidgetViews(context, failed, settings, 88_046, compact = true).apply(context, FrameLayout(context))
            assertEquals("—", failedView.findViewById<TextView>(R.id.widget_status_detail).text.toString())
            assertEquals(View.GONE, failedView.findViewById<View>(R.id.widget_status_flag).visibility)
            assertTrue(failedView.findViewById<View>(R.id.widget_status_power_touch).isEnabled)
        }
    }

    @Test
    fun pendingLocationShowsDeterminingInBothWidgetSizesAndLanguages() {
        instrumentation.runOnMainSync {
            val oldLocation = VpnLocation("DE", "Berlin", "Germany", "192.0.2.1")
            val base = VpnUiState(isReady = true, location = oldLocation,
                deviceLocation = VpnDeviceLocation(oldLocation, DeviceLocationStatus.CURRENT, 123L))
            val requests = listOf(
                base.copy(phase = VpnPhase.CONNECTING),
                base.copy(phase = VpnPhase.VALIDATING, hasOwnedTunnel = true),
                base.copy(deviceLocation = base.deviceLocation.copy(status = DeviceLocationStatus.LOADING)),
            )
            listOf(false, true).forEach { compact ->
                listOf(VpnLanguage.EN to "Determining…", VpnLanguage.RU to "Определение…").forEach { (language, expected) ->
                    requests.forEach { pending ->
                        val view = statusWidgetViews(context, pending, VpnSettings(language = language), 88_047, compact)
                            .apply(context, FrameLayout(context))
                        assertEquals(expected, view.findViewById<TextView>(R.id.widget_status_detail).text.toString())
                        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_status_flag).visibility)
                        val localized = widgetLocalizedContext(context, language)
                        assertEquals(localized.getString(widgetStatusLabel(pending)), view.findViewById<TextView>(R.id.widget_status_label).text.toString())
                    }
                }
            }
        }
    }

    @Test
    fun connectionWidgetCentersTheGroupAndRetainsTheMinimumFullWidthAction() {
        instrumentation.runOnMainSync {
            listOf(false, true).forEach { compact ->
                listOf(240, 241, 280, 400).forEach { widthDp ->
                    val width = dp(widthDp)
                    val height = dp(if (compact) 110 else 200)
                    val state = VpnUiState(isReady = true)
                    val view = statusWidgetViews(context, state, VpnSettings(language = VpnLanguage.EN),
                        88_050, compact, widthDp).apply(context, FrameLayout(context))
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, width, height)
                    val power = view.findViewById<View>(R.id.widget_status_power_touch)
                    val logo = view.findViewById<View>(R.id.widget_status_logo_touch)
                    assertTrue(power.measuredWidth >= dp(48))
                    assertTrue(power.measuredHeight >= dp(48))
                    assertTrue(logo.measuredWidth >= dp(48))
                    assertTrue(logo.measuredHeight >= dp(48))
                    val expected = dp(240) - 2 * dp(12)
                    assertEquals(expected, power.measuredWidth)
                    assertEquals(expected, view.findViewById<View>(R.id.widget_status_power_ring).measuredWidth)
                    val root = view as ViewGroup
                    assertFalse(root.isAttachedToWindow)
                    fun boundsInRoot(child: View): Rect {
                        val bounds = Rect(0, 0, child.width, child.height)
                        root.offsetDescendantRectToMyCoords(child, bounds)
                        return bounds
                    }
                    val logoBounds = boundsInRoot(logo)
                    val statusBounds = boundsInRoot(view.findViewById(R.id.widget_status_label))
                    val headerBounds = boundsInRoot(view.findViewById(R.id.widget_status_header))
                    val powerBounds = boundsInRoot(power)
                    assertTrue(kotlin.math.abs(powerBounds.left - (width - powerBounds.right)) <= 1)
                    assertTrue(kotlin.math.abs(headerBounds.left - (width - headerBounds.right)) <= 1)
                    assertTrue(logoBounds.right <= statusBounds.left)
                    assertTrue(logoBounds.top < statusBounds.bottom)
                    assertTrue(statusBounds.top < logoBounds.bottom)
                    assertTrue(statusBounds.top < powerBounds.top)
                    assertTrue(powerBounds.bottom <= height)
                }
            }
        }
    }

    @Test
    fun actionLabelsAndColorsFollowConnectionAcrossThemesAndLanguages() {
        instrumentation.runOnMainSync {
            val profile = VpnProfileSummary("widget-profile", "Profile", "VLESS")
            val idle = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)
            val connected = idle.copy(phase = VpnPhase.CONNECTED, hasOwnedTunnel = true)
            listOf(false, true).forEach { compact ->
                VpnTheme.entries.forEach { theme ->
                    listOf(VpnLanguage.EN, VpnLanguage.RU).forEach { language ->
                        listOf(idle to false, connected to true).forEach { (state, stopping) ->
                            val view = statusWidgetViews(context, state, VpnSettings(theme = theme, language = language), 88_048, compact)
                                .apply(context, FrameLayout(context))
                            val label = view.findViewById<TextView>(R.id.widget_status_power_label)
                            val expected = if (language == VpnLanguage.RU) {
                                if (stopping) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
                            } else {
                                if (stopping) "DISCONNECT" else "CONNECT"
                            }
                            assertEquals(expected, label.text.toString())
                            assertEquals(expected, view.findViewById<View>(R.id.widget_status_power_touch).contentDescription)
                            val color = label.currentTextColor
                            if (stopping) {
                                assertTrue(android.graphics.Color.red(color) > android.graphics.Color.green(color))
                            } else {
                                assertTrue(android.graphics.Color.green(color) > android.graphics.Color.red(color))
                            }
                            assertFalse(view.findViewById<View>(R.id.widget_status_root).hasOnClickListeners())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reapplyingAColdOrStoppingStateRemovesThePreviousAction() {
        instrumentation.runOnMainSync {
            val profile = VpnProfileSummary("widget-profile", "Profile", "VLESS")
            val idle = VpnUiState(isReady = true, profiles = listOf(profile), selectedProfileId = profile.id)
            val settings = VpnSettings(language = VpnLanguage.EN)
            listOf(false, true).forEach { compact ->
                listOf(VpnUiState(), idle.copy(phase = VpnPhase.STOPPING)).forEach { pending ->
                    val view = statusWidgetViews(context, idle, settings, 88_049, compact).apply(context, FrameLayout(context))
                    assertTrue(view.findViewById<View>(R.id.widget_status_power_touch).hasOnClickListeners())
                    statusWidgetViews(context, pending, settings, 88_049, compact).reapply(context, view)
                    val action = view.findViewById<View>(R.id.widget_status_power_touch)
                    assertFalse(action.isEnabled)
                    assertFalse(action.hasOnClickListeners())
                    assertTrue(view.findViewById<View>(R.id.widget_status_logo_touch).hasOnClickListeners())
                    val localized = widgetLocalizedContext(context, VpnLanguage.EN)
                    assertEquals(
                        localized.getString(widgetStatusLabel(pending)),
                        view.findViewById<TextView>(R.id.widget_status_label).text.toString(),
                    )
                    assertEquals(
                        if (pending.isReady) "DISCONNECTING" else "LOADING",
                        view.findViewById<TextView>(R.id.widget_status_power_label).text.toString(),
                    )
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun shellOutput(command: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return legacyShellOutput(command)
        val descriptors = instrumentation.uiAutomation.executeShellCommandRwe(command)
        return try {
            descriptors[1].close()
            val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
            val errors = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
            output + errors
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    private fun legacyShellOutput(command: String): String {
        check(command.matches(Regex("[A-Za-z0-9_./ -]+")))
        val directory = checkNotNull(context.externalCacheDir)
        val script = File(directory, "foxhole-boundary-${UUID.randomUUID()}.sh")
        check(script.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        try {
            // Runtime.exec(String) does not parse shell quoting; the shell reads this own fixture.
            script.writeText("#!/system/bin/sh\n$command 2>&1\n")
            val descriptor = instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        } finally {
            check(!script.exists() || script.delete()) { "Could not remove the shell boundary fixture" }
        }
    }
}
