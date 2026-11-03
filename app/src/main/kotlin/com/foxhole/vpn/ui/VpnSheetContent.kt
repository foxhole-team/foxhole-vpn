package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.foxhole.vpn.R

@Composable
internal fun VpnSheetContent(title: Int, icon: Int, modifier: Modifier = Modifier, busy: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}, titleHelp: Int? = null, titleBadge: Int? = null,
    scrollableBody: Boolean = false, footer: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
                tint = if (icon == R.drawable.lin_trash) vpnDeleteColor() else LocalContentColor.current)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val titleStyle = if (titleBadge != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                Text(stringResource(title), Modifier.weight(1f, fill = titleBadge == null), style = titleStyle,
                    maxLines = if (titleBadge != null) 1 else Int.MAX_VALUE)
                titleBadge?.let { badge ->
                    Surface(modifier = Modifier.offset(y = (-6).dp), shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(stringResource(badge), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                    }
                }
            }
            if (busy || titleBadge == null) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    val working = stringResource(R.string.vpn_working)
                    CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
                }
            }
            if (titleHelp != null) VpnRouterHelpButton(title, titleHelp, actionStyle = true)
            actions()
        }
        if (scrollableBody) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
        } else content()
        footer?.invoke(this)
    }
}

@Composable
internal fun VpnRouterSheetContent(
    title: Int,
    icon: Int,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    titleHelp: Int? = null,
    naturalHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.then(if (naturalHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
        .padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
                tint = if (icon == R.drawable.lin_trash) vpnDeleteColor() else LocalContentColor.current)
            Text(stringResource(title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (busy) {
                val working = stringResource(R.string.vpn_working)
                CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = working }, strokeWidth = 2.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (titleHelp != null) VpnRouterHelpButton(title, titleHelp, actionStyle = true)
                actions()
            }
        }
        val body = Modifier.weight(1f, fill = !naturalHeight).fillMaxWidth().then(LocalVpnSheetBodyGestures.current)
        Column(if (scrollable) body.verticalScroll(rememberScrollState()) else body,
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        if (footer != null) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).then(LocalVpnSheetBodyGestures.current),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}
