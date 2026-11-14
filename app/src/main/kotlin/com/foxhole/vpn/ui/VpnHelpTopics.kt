package com.foxhole.vpn.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.foxhole.vpn.R

internal data class VpnHelpTopic(val id: String, @StringRes val title: Int,
    @DrawableRes val icon: Int, val blocks: List<HelpBlock>)

internal fun vpnHelpTopics(canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): List<VpnHelpTopic> = listOf(
    VpnHelpTopic("import", R.string.vpn_help_add_title, R.drawable.lin_plus, listOf(
        HelpBlock.Items(listOf(R.string.help_import_link, R.string.help_import_file, R.string.help_import_qr, R.string.help_import_manual)),
        HelpBlock.Notice(R.string.help_profile_secret))),
    VpnHelpTopic("connect", R.string.vpn_help_connect_title, R.drawable.lin_power, listOf(
        HelpBlock.Items(listOf(R.string.help_connect_select, R.string.help_connect_start, R.string.help_connect_result), numbered = true))),
    VpnHelpTopic("profiles", R.string.vpn_help_profiles_title, R.drawable.lin_profiles, listOf(
        HelpBlock.Paragraph(R.string.vpn_help_profiles_body), HelpBlock.Notice(R.string.help_profile_secret))),
    VpnHelpTopic("protocols", R.string.vpn_help_protocols_title, R.drawable.lin_file, listOf(
        HelpBlock.Chips(listOf("VLESS", "VMess", "Trojan", "Shadowsocks / Outline", "WireGuard", "AmneziaWG", "Hysteria2", "Naive", "TUIC", "AnyTLS")),
        HelpBlock.Notice(R.string.help_amnezia_experimental),
        HelpBlock.Details("import-formats", R.string.help_details, listOf(HelpBlock.Paragraph(R.string.help_protocol_formats))))),
    VpnHelpTopic("routing", R.string.vpn_scenarios, R.drawable.lin_apps, applicationHelpBlocks()),
    VpnHelpTopic("router", R.string.help_router_title, R.drawable.lin_router, vpnRouterHelpBlocks(canInstallRouter, canInspectRouter)),
    VpnHelpTopic("updates", R.string.vpn_help_updates_title, R.drawable.lin_refresh,
        listOf(HelpBlock.Items(listOf(R.string.help_updates_subscriptions, R.string.help_updates_db, R.string.help_updates_app)))),
    VpnHelpTopic("privacy", R.string.vpn_help_privacy_title, R.drawable.lin_info, listOf(
        HelpBlock.Comparison(null, listOf(
            HelpRow(HelpText.Literal("Google"), HelpText.Resource(R.string.help_privacy_google)),
            HelpRow(HelpText.Literal("ipwho.is"), HelpText.Resource(R.string.help_privacy_ipwho), code = true))), HelpBlock.Notice(R.string.help_wg_proof), HelpBlock.Paragraph(R.string.help_privacy_local))),
)

internal fun vpnHelpTopic(id: String, canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): VpnHelpTopic =
    vpnHelpTopics(canInstallRouter, canInspectRouter).first { it.id == id }

internal fun vpnRouterContextBlocks(@StringRes resource: Int, canInstallRouter: Boolean = false, canInspectRouter: Boolean = true): List<HelpBlock>? = when (resource) {
    R.string.router_help_setup_pair -> vpnRouterHelpBlocks(canInstallRouter, canInspectRouter)
    R.string.router_help_profile -> listOf(HelpBlock.Paragraph(R.string.help_router_transfer), HelpBlock.Notice(R.string.help_router_profile_secret))
    R.string.router_help_home -> listOf(HelpBlock.Notice(R.string.help_router_home))
    R.string.router_help_public_ip, R.string.router_review_remote_help, R.string.router_help_remote_clients ->
        listOf(HelpBlock.Paragraph(R.string.help_router_reverse), HelpBlock.Notice(R.string.help_router_public_ip))
    else -> null
}

internal fun applicationHelpBlocks(): List<HelpBlock> = listOf(
    HelpBlock.Heading(R.string.site_routing_modes_title),
    HelpBlock.Items(listOf(R.string.help_route_whole, R.string.help_route_proxy, R.string.help_route_default)),
    HelpBlock.Heading(R.string.site_routing_direct_title), HelpBlock.Paragraph(R.string.help_route_direct),
    HelpBlock.Heading(R.string.site_routing_block_title), HelpBlock.Paragraph(R.string.help_route_block),
    HelpBlock.Notice(R.string.help_wg_platform), HelpBlock.Paragraph(R.string.vpn_scenario_atomic_help),
    HelpBlock.Paragraph(R.string.help_route_switch),
)

internal fun websiteHelpBlocks(): List<HelpBlock> = listOf(
    HelpBlock.Paragraph(R.string.vpn_web_rules_help),
    HelpBlock.Comparison(R.string.site_mask_format, listOf(
        maskRow(R.string.site_mask_exact_example, R.string.site_mask_exact_scope),
        maskRow(R.string.site_mask_suffix_example, R.string.site_mask_suffix_scope),
        maskRow(R.string.site_mask_zone_example, R.string.site_mask_zone_scope),
        maskRow(R.string.site_mask_url_example, R.string.site_mask_url_scope),
        maskRow(R.string.site_mask_idn_example, R.string.site_mask_idn_scope),
        maskRow(R.string.site_mask_ip_example, R.string.site_mask_ip_scope),
        maskRow(R.string.site_mask_cidr_example, R.string.site_mask_cidr_scope),
        maskRow(R.string.site_mask_range_example, R.string.site_mask_range_scope))),
    HelpBlock.Notice(R.string.site_mask_unsupported_formats), HelpBlock.Notice(R.string.help_wg_platform),
)

internal fun vpnRouterHelpBlocks(canInstallRouter: Boolean, canInspectRouter: Boolean = true): List<HelpBlock> = listOf(
    HelpBlock.Heading(R.string.help_router_preparation, R.drawable.lin_router),
    HelpBlock.Items(listOf(R.string.help_router_wifi,
        when { canInstallRouter -> R.string.help_router_install_available; canInspectRouter -> R.string.help_router_install_unavailable; else -> R.string.help_router_install_manual }), numbered = true, links = routerInstallationLinks()),
    HelpBlock.Heading(R.string.help_router_connection, R.drawable.lin_power),
    HelpBlock.Items(listOf(R.string.help_router_pin, R.string.help_router_pair), numbered = true, start = 3),
    HelpBlock.Heading(R.string.help_router_profiles, R.drawable.lin_profiles),
    HelpBlock.Items(listOf(R.string.help_router_transfer), numbered = true, start = 5),
    HelpBlock.Paragraph(R.string.help_router_protocols),
    HelpBlock.Notice(R.string.help_router_footer),
    HelpBlock.Details("router-details", R.string.help_details, listOf(
        HelpBlock.Paragraph(R.string.help_router_reverse), HelpBlock.Notice(R.string.help_router_profile_secret),
        HelpBlock.Notice(R.string.help_router_public_ip), HelpBlock.Notice(R.string.help_router_home),
        HelpBlock.Notice(R.string.help_router_trust))),
)

private fun maskRow(label: Int, detail: Int): HelpRow = HelpRow(HelpText.Resource(label), HelpText.Resource(detail), code = true)

private fun routerInstallationLinks(): Map<Int, HelpInlineLink> = listOf(
    R.string.help_router_install_available, R.string.help_router_install_unavailable, R.string.help_router_install_manual,
).associateWith { HelpInlineLink(R.string.help_router_install_link, "https://github.com/foxhole-team/foxhole-openwrt-client") }
