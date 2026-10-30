package com.foxhole.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.core.content.getSystemService
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.view.View
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ProfileClipboardAndroidTest {
    @get:Rule val compose = createComposeRule()
    @Test fun multipleClipboardItemsStayTogetherAndRespectByteAndCountLimits() {
        val clip = ClipData.newPlainText("Owned profiles", "owned-first")
        clip.addItem(ClipData.Item("owned-second"))
        assertEquals("owned-first\nowned-second", profileClipboardText(clip))
        assertNull(profileClipboardText(ClipData.newPlainText("Owned large input", "€".repeat(350_000))))
        val tooMany = ClipData.newPlainText("Owned excessive count", "owned")
        repeat(256) { tooMany.addItem(ClipData.Item("owned")) }
        assertNull(profileClipboardText(tooMany))
    }

    @Test fun copiedProfilesAreSensitiveAndReadableThroughTheSameClipboardPath() {
        val ownedView = AtomicReference<View>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { ownedView.set(view) }
            Text("Owned clipboard foreground")
        }
        compose.onNodeWithText("Owned clipboard foreground").assertIsDisplayed()
        val focusStarted = SystemClock.elapsedRealtime()
        try { compose.waitUntil(5_000) { compose.runOnIdle { ownedView.get()?.hasWindowFocus() == true } } }
        catch (failure: Throwable) {
            reportClipboardFocusFailure(listOfNotNull(ownedView.get()), SystemClock.elapsedRealtime() - focusStarted)
            throw failure
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val value = "vless://00000000-0000-4000-8000-000000000001@8.8.8.8:443?security=tls#Owned"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(requireNotNull(ownedView.get()).hasWindowFocus())
            copyProfileClipboard(context, value)
            val clip = requireNotNull(context.getSystemService<ClipboardManager>()?.primaryClip)
            assertTrue(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
            assertEquals(value, readProfileClipboard(context))
        }
    }
}

internal fun reportClipboardFocusFailure(views: List<View>, elapsedMillis: Long) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    try {
        instrumentation.runOnMainSync {
            val keyguard = context.getSystemService<KeyguardManager>()
            Log.e("ClipboardTestFocus", "waitMs=$elapsedMillis, sampleUptime=${SystemClock.uptimeMillis()}, " +
                "interactive=${context.getSystemService<PowerManager>()?.isInteractive}, " +
                "keyguardLocked=${keyguard?.isKeyguardLocked}, deviceLocked=${keyguard?.isDeviceLocked}")
            val roots = if (Build.VERSION.SDK_INT >= 29) views + WindowInspector.getGlobalWindowViews() else views
            roots.map(View::getRootView).distinct().take(8).forEachIndexed { index, root ->
                val params = root.layoutParams as? WindowManager.LayoutParams
                Log.e("ClipboardTestFocus", "root=$index, nativeFocus=${root.hasWindowFocus()}, attached=${root.isAttachedToWindow}, " +
                    "shown=${root.isShown}, type=${params?.type}, flags=${params?.flags?.toString(16)}, " +
                    "notFocusable=${params?.let { it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 }}, " +
                    "display=${root.display?.displayId}, displayState=${root.display?.state}, " +
                    "owned=${root.context.packageName == context.packageName}")
            }
        }
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys window"))
            .use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var remaining = 1024 * 1024
                while (remaining > 0) {
                    val size = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (size < 0) break
                    output.write(buffer, 0, size)
                    remaining -= size
                }
                output.toString(Charsets.UTF_8.name())
            }
        val component = Regex("\\b[A-Za-z][A-Za-z0-9_.]+/[.A-Za-z_$][A-Za-z0-9_.$]+")
        val focus = dump.lineSequence().mapNotNull { line ->
            val field = when {
                line.trimStart().startsWith("mCurrentFocus=") -> "currentFocus"
                line.trimStart().startsWith("mFocusedApp=") -> "focusedApp"
                else -> return@mapNotNull null
            }
            val value = component.find(line)?.value ?: if (line.endsWith("=null")) "null" else "unparsed"
            field to value
        }.take(2).toMap()
        for (field in listOf("currentFocus", "focusedApp")) Log.e("ClipboardTestFocus", "$field=${focus[field] ?: "absent"}")
    } catch (failure: Exception) { Log.e("ClipboardTestFocus", "diagnosticFailure=${failure.javaClass.simpleName}") }
}
