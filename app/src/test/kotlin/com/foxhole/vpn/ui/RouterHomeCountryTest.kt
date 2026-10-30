package com.foxhole.vpn.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterHomeCountryTest {
    @Test fun onlyKnownDeviceCountryCodesCanPrefillTheWizard() {
        assertEquals("NL", knownRouterHomeCountry("nl"))
        for (value in listOf(null, "", "ZZ", "Netherlands", "N1")) assertNull(knownRouterHomeCountry(value))
    }
    @Test fun unknownDeviceCountryNeverFallsBackToTheSystemLocaleCountry() {
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale.US); assertNull(knownRouterHomeCountry(null)); assertNull(knownRouterHomeCountry("ZZ")) }
        finally { Locale.setDefault(previous) }
    }
}
