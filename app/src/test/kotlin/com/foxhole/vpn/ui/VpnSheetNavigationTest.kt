package com.foxhole.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSheetNavigationTest {
    @Test fun backReturnsTheSameActionEntryAndASecondOldCallbackCannotPopItsParent() {
        val navigation = VpnSheetNavigation()
        navigation.open(VpnSheetPage.Settings)
        navigation.push(VpnSheetPage.Profiles)
        val profile = requireNotNull(navigation.current)
        navigation.push(VpnSheetPage.RemoveConfirmation("owned", 3))
        val confirmation = requireNotNull(navigation.current)
        assertEquals(2, confirmation.depth)
        assertTrue(navigation.back(confirmation.id))
        assertEquals(profile, navigation.current)
        assertFalse(navigation.back(confirmation.id))
        assertEquals(profile, navigation.current)
    }

    @Test fun aLateDismissCannotCloseAReplacementRoute() {
        val navigation = VpnSheetNavigation()
        navigation.open(VpnSheetPage.ImportSources)
        val old = requireNotNull(navigation.current)
        navigation.open(VpnSheetPage.Settings)
        val current = requireNotNull(navigation.current)
        navigation.close(old.id)
        assertEquals(current, navigation.current)
        navigation.close(current.id)
        assertNull(navigation.current)
    }

    @Test fun consentBackReturnsTheExistingImportPage() {
        val navigation = VpnSheetNavigation()
        navigation.open(VpnSheetPage.ImportSources)
        navigation.push(VpnSheetPage.ImportText)
        val input = requireNotNull(navigation.current)
        navigation.push(VpnSheetPage.InsecureConsent)
        assertTrue(navigation.back(requireNotNull(navigation.current).id))
        assertEquals(input, navigation.current)
        assertTrue(navigation.back(input.id))
        assertEquals(VpnSheetPage.ImportSources, navigation.current?.page)
    }

    @Test fun replacingAnEditorInvalidatesOldCallbacksAndKeepsItsActualParent() {
        val navigation = VpnSheetNavigation()
        navigation.open(VpnSheetPage.ImportSources)
        val sources = requireNotNull(navigation.current)
        navigation.push(VpnSheetPage.ImportText)
        val oldInput = requireNotNull(navigation.current)
        navigation.replace(VpnSheetPage.ImportText)
        val replacement = requireNotNull(navigation.current)
        assertTrue(replacement.id != oldInput.id)
        assertEquals(oldInput.depth, replacement.depth)
        assertFalse(navigation.back(oldInput.id))
        navigation.close(oldInput.id)
        assertEquals(replacement, navigation.current)
        navigation.push(VpnSheetPage.InsecureConsent)
        val consent = requireNotNull(navigation.current)
        assertTrue(navigation.back(consent.id))
        navigation.replace(VpnSheetPage.ImportText)
        val newest = requireNotNull(navigation.current)
        assertTrue(newest.id != replacement.id)
        assertEquals(replacement.depth, newest.depth)
        assertFalse(navigation.back(consent.id))
        assertTrue(navigation.back(newest.id))
        assertEquals(sources, navigation.current)
    }
}
