package com.foxhole.vpn.widget

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.vpn.DeviceLocationStatus
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnDeviceLocation
import com.foxhole.vpn.VpnLanguage
import com.foxhole.vpn.VpnLocation
import com.foxhole.vpn.VpnSettings
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.flags.countryFlagBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class WidgetFlagRenderingAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun canonicalCountryBitmapsRetainTheirSourceGeometry() {
        for (code in listOf("GB", "RU", "US", "UM", "GU", "NP", "NL")) {
            val bitmap = requireNotNull(countryFlagBitmap(context, code))
            assertEquals(256, bitmap.width)
            assertEquals(192, bitmap.height)
        }
    }

    @Test fun invalidOrMissingCountryCodesNeverLoadAFallbackEmojiOrUnboundedImage() {
        assertNull(countryFlagBitmap(context, "../de"))
        assertNull(countryFlagBitmap(context, "zz"))
    }

    @Test fun bothRemoteViewLayoutsMatchTheActualDetailCapitalAtEveryFontScale() {
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.3f, 2f)) for (compact in listOf(false, true)) {
                val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    this.fontScale = fontScale
                })
                val state = VpnUiState(isReady = true, deviceLocation = VpnDeviceLocation(
                    VpnLocation("DE", "Berlin", "Germany", "192.0.2.1"), DeviceLocationStatus.CURRENT, 123L))
                val view = statusWidgetViews(scaled, state, VpnSettings(language = VpnLanguage.EN), 88_061, compact)
                    .apply(scaled, FrameLayout(scaled))
                val density = scaled.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((280 * density).roundToInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((400 * density).roundToInt(), View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val text = view.findViewById<TextView>(R.id.widget_status_detail)
                val bounds = Rect()
                text.paint.getTextBounds("H", 0, 1, bounds)
                val flag = view.findViewById<ImageView>(R.id.widget_status_flag)
                assertEquals(View.VISIBLE, flag.visibility)
                assertTrue(flag.drawable != null)
                assertEquals(bounds.height(), flag.measuredHeight)
                assertEquals((bounds.height() * 4f / 3f).roundToInt(), flag.measuredWidth)
                assertEquals("Berlin", text.text.toString())
            }
        }
    }
}
