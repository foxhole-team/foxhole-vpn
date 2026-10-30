package com.foxhole.vpn.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfilesViewportTest {
    @Test fun collapsedViewportContainsSixRowsAndFiveGapsThenStopsGrowing() {
        assertEquals(424.dp, vpnProfilesViewportHeight(6, false, 64.dp))
        assertEquals(424.dp, vpnProfilesViewportHeight(7, false, 64.dp))
        assertEquals(64.dp, vpnProfilesViewportHeight(1, false, 64.dp))
        assertEquals(0.dp, vpnProfilesViewportHeight(0, false, 64.dp))
    }

    @Test fun expandedProtocolsAndLargeTextHaveRoomWithoutReducingTouchTargets() {
        assertEquals(424.dp, vpnProfilesViewportHeight(1, true, 64.dp))
        assertEquals(616.dp, vpnProfilesViewportHeight(7, false, 96.dp))
    }

    @Test fun separateRowAndSpacingPixelRoundingNeverClipsTheSixthRow() = with(Density(2.6875f)) {
        val viewport = vpnProfilesViewportHeight(7, false, 64.dp, this)
        assertEquals(6 * 64.dp.roundToPx() + 5 * 8.dp.roundToPx(), viewport.roundToPx())
    }

    @Test fun scrollbarStaysWithinItsTrackAndMovesToTheEndOfTheActualRange() {
        val start = requireNotNull(vpnProfileScrollThumb(0, 424, 496, 424f, 24f))
        val end = requireNotNull(vpnProfileScrollThumb(72, 424, 496, 424f, 24f))
        assertEquals(0f, start.top, .001f)
        assertEquals(424f, end.top + end.height, .001f)
        assertTrue(start.height >= 24f)
        assertTrue(end.top > start.top)
    }

    @Test fun fittedUnknownAndInvalidRangesDoNotDrawAFakeScrollbar() {
        assertNull(vpnProfileScrollThumb(0, 424, 424, 424f, 24f))
        assertNull(vpnProfileScrollThumb(Int.MAX_VALUE, 424, 496, 424f, 24f))
        assertNull(vpnProfileScrollThumb(0, 0, 496, 424f, 24f))
        assertNull(vpnProfileScrollThumb(0, 424, 496, Float.NaN, 24f))
    }
}
