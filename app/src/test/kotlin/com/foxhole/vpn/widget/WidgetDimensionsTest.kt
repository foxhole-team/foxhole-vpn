package com.foxhole.vpn.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class WidgetDimensionsTest {
    @Test fun resizingCentersTheSharedHeaderAndActionContainer() {
        listOf(1f, 1.5f, 2.625f, 3f).forEach { density ->
            var previousContent = 0
            listOf(180, 239, 240, 241, 280, 300, 400, 640).forEach { width ->
                val (left, right) = widgetStatusActionInsets(width, density)
                assertEquals(left, right)
                assertTrue(left >= 0)
                val content = (width * density).roundToInt() - left - right
                assertTrue(content <= (300 * density).roundToInt() + 1)
                assertTrue(content >= previousContent - 1)
                assertTrue(content >= (48 * density).roundToInt())
                if (width <= 300) assertEquals(0 to 0, left to right)
                previousContent = content
            }
        }
    }

    @Test fun anUndersizedHostNeverProducesNegativeInsets() {
        assertEquals(0 to 0, widgetStatusActionInsets(-1, density = 1f))
        assertEquals(0 to 0, widgetStatusActionInsets(0, density = 1f))
    }

}
