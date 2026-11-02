package com.foxhole.vpn.appupdates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class VpnAppUpdateChecksumsTest {
    @Test fun checksumsRequireOneExactMatchingApkDigest() {
        val digest = "a".repeat(64)
        val row = "$digest  update.apk"
        assertTrue(vpnAppUpdateChecksumMatches(row, "update.apk", digest))
        assertTrue(vpnAppUpdateChecksumMatches("$digest *update.apk\n", "update.apk", digest.uppercase()))
        assertFalse(vpnAppUpdateChecksumMatches(row, "other.apk", digest))
        assertFalse(vpnAppUpdateChecksumMatches(row, "update.apk", "b".repeat(64)))
        assertFalse(vpnAppUpdateChecksumMatches("$row\n$row", "update.apk", digest))
        assertFalse(vpnAppUpdateChecksumMatches("malformed\n$row", "update.apk", digest))
        assertFalse(vpnAppUpdateChecksumMatches("$digest  ../update.apk", "update.apk", digest))
    }
}
