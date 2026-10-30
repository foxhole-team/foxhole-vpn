package com.foxhole.vpn.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class WidgetDimensionsTest {
    @Test fun resizingRetainsTheMinimumFullWidthButtonWithSymmetricInsets() {
        listOf(1f, 1.5f, 2.625f, 3f).forEach { density ->
            val padding = 2 * (12 * density).roundToInt()
            val minimum = (240 * density).roundToInt() - padding
            listOf(180, 239, 240, 241, 280, 400).forEach { width ->
                val (left, right) = widgetStatusActionInsets(width, density)
                val available = (width * density).roundToInt() - padding
                assertEquals(if (width <= 240) available else minimum, available - left - right)
                assertTrue(abs(left - right) <= 1)
            }
        }
    }

    @Test fun anUndersizedHostNeverProducesNegativeInsets() {
        assertEquals(0 to 0, widgetStatusActionInsets(-1, density = 1f))
        assertEquals(0 to 0, widgetStatusActionInsets(0, density = 1f))
    }
}
