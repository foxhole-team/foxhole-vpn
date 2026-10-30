package com.foxhole.vpn.appupdates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppUpdatePolicyTest {
    @Test fun onlyThePublicGitHubPackageCanSelfUpdate() {
        assertTrue(VpnAppUpdatePolicy.allowed("com.foxhole.vpn", "github"))
        assertFalse(VpnAppUpdatePolicy.allowed("com.foxhole.vpn.debug", "github"))
        assertFalse(VpnAppUpdatePolicy.allowed("com.foxhole.vpn", "fdroid"))
        assertFalse(VpnAppUpdatePolicy.allowed("com.foxhole.guard", "github"))
    }

    @Test fun releaseAssetsBelongToTheVpnRepository() {
        assertTrue(VpnAppUpdatePolicy.releaseAssetUrl("https://github.com/foxhole-team/foxhole-vpn/releases/download/v0.1.1/vpn.apk"))
        assertFalse(VpnAppUpdatePolicy.releaseAssetUrl("https://github.com/foxhole-team/foxhole-guard/releases/download/v0.1.1/vpn.apk"))
        assertFalse(VpnAppUpdatePolicy.releaseAssetUrl("https://github.com/foxhole-team/foxhole-vpn/releases/download/../vpn.apk"))
        assertFalse(VpnAppUpdatePolicy.releaseAssetUrl("https://github.com:8443/foxhole-team/foxhole-vpn/releases/download/v0.1.1/vpn.apk"))
    }

    @Test fun redirectTargetsCannotLeaveTheHttpsGitHubCdnAllowlist() {
        assertTrue(VpnAppUpdatePolicy.downloadHopAllowed("https://release-assets.githubusercontent.com/github-production-release-asset/123/file?x=1"))
        listOf("http://github.com/a", "https://127.0.0.1/a", "https://github.com.evil.example/a",
            "https://user:secret@github.com/a", "https://release-assets.githubusercontent.com:444/a").forEach {
            assertFalse(it, VpnAppUpdatePolicy.downloadHopAllowed(it))
        }
    }

    @Test fun packageChecksRefuseDowngradesAndUnpinnedOrMissingSigners() {
        val canonical = setOf(VpnAppUpdatePolicy.RELEASE_CERT_SHA256)
        assertTrue(VpnAppUpdatePolicy.packageMatches("com.foxhole.vpn", "com.foxhole.vpn", 19, 18, 19, "0.1.2", "0.1.2", canonical, canonical))
        assertFalse(VpnAppUpdatePolicy.packageMatches("com.foxhole.vpn", "com.foxhole.vpn", 18, 18, 18, "0.1.1", "0.1.1", canonical, canonical))
        assertFalse(VpnAppUpdatePolicy.packageMatches("com.foxhole.guard", "com.foxhole.vpn", 19, 18, 19, "0.1.2", "0.1.2", canonical, canonical))
        assertFalse(VpnAppUpdatePolicy.packageMatches("com.foxhole.vpn", "com.foxhole.vpn", 19, 18, 19, "0.1.2", "0.1.2", setOf("a".repeat(64)), setOf("a".repeat(64))))
        assertFalse(VpnAppUpdatePolicy.packageMatches("com.foxhole.vpn", "com.foxhole.vpn", 19, 18, 19, "0.1.2", "0.1.2", canonical, emptySet()))
    }

    @Test fun manifestNamesCannotEscapePrivateStorage() {
        assertTrue(VpnAppUpdateManifest(19, "0.1.2", "foxhole-vpn-0.1.2.apk", "b".repeat(64)).isValid)
        listOf("../evil.apk", "/evil.apk", "dir/evil.apk", "a\\evil.apk", "update.json").forEach {
            assertFalse(VpnAppUpdateManifest(19, "0.1.2", it, "b".repeat(64)).isValid)
        }
        assertEquals("https://api.github.com/repos/foxhole-team/foxhole-vpn/releases/latest", VpnAppUpdatePolicy.RELEASES_API)
    }
}
