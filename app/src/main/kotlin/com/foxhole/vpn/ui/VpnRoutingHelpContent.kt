package com.foxhole.vpn.ui

import androidx.compose.runtime.Composable

@Composable
internal fun VpnApplicationRoutingHelp() {
    VpnHelpBlocks(applicationHelpBlocks(), scope = "application-rules")
}

@Composable
internal fun VpnWebsiteMaskHelp() {
    VpnHelpBlocks(websiteHelpBlocks(), scope = "website-masks")
}
