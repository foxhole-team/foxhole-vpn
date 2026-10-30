package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.router.RouterBetaNoticePreferences

@Composable
internal fun VpnRouterBetaNoticeContent() {
    val colors = LocalVpnColors.current
    val uriHandler = LocalUriHandler.current
    val body = stringResource(R.string.router_beta_notice_body)
    val text = remember(body, colors.foreground, uriHandler) {
        buildAnnotatedString {
            val start = body.indexOf(CLIENT_LABEL)
            if (start < 0) append(body) else {
                append(body.substring(0, start))
                withLink(LinkAnnotation.Url(REPOSITORY_URL,
                    TextLinkStyles(style = SpanStyle(color = colors.foreground, textDecoration = TextDecoration.Underline))) {
                    runCatching { uriHandler.openUri(REPOSITORY_URL) }
                }) { append(CLIENT_LABEL) }
                append(body.substring(start + CLIENT_LABEL.length))
            }
        }
    }
    VpnSheetContent(R.string.router_beta_notice_title, R.drawable.lin_info,
        modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
    }
}

@Composable
internal fun VpnRouterBetaNoticeOptOut(preferences: RouterBetaNoticePreferences) {
    var checked by rememberSaveable { mutableStateOf(preferences.isSuppressed()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Checkbox, onValueChange = {
                checked = it
                preferences.setSuppressed(it)
            }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(R.string.router_beta_notice_dont_show_again), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = LocalVpnColors.current.foreground)
    }
}

private const val CLIENT_LABEL = "FoxHole OpenWrt Client"
private const val REPOSITORY_URL = "https://github.com/foxhole-team/foxhole-openwrt-client"
