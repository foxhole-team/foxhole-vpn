package com.foxhole.vpn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.vpn.R


@Composable
internal fun VpnHelpBlocks(blocks: List<HelpBlock>, modifier: Modifier = Modifier, scope: String = "help") {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { index, block ->
            key(scope, index) {
                when (block) {
                    is HelpBlock.Heading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        block.icon?.let { Icon(painterResource(it), null, Modifier.size(22.dp), tint = LocalVpnColors.current.muted) }
                        Text(stringResource(block.text), Modifier.weight(1f).semantics { heading() },
                            color = LocalVpnColors.current.foreground, style = MaterialTheme.typography.titleSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif))
                    }
                    is HelpBlock.Paragraph -> HelpParagraph(stringResource(block.text))
                    is HelpBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        block.items.forEachIndexed { item, text ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HelpParagraph(if (block.numbered) "${item + block.start}." else "•")
                                HelpParagraph(stringResource(text), Modifier.weight(1f), link = block.links[text])
                            }
                        }
                    }
                    is HelpBlock.Notice -> Surface(Modifier.fillMaxWidth(), color = LocalVpnColors.current.panel,
                        border = BorderStroke(1.dp, LocalVpnColors.current.border), shape = MaterialTheme.shapes.small) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.lin_info), null, Modifier.size(20.dp), tint = LocalVpnColors.current.muted)
                            HelpParagraph(stringResource(block.text), Modifier.weight(1f))
                        }
                    }
                    is HelpBlock.Comparison -> HelpComparison(block)
                    is HelpBlock.Chips -> HelpProtocolChips(block.labels)
                    is HelpBlock.Details -> HelpDetails(block, "$scope/$index")
                    is HelpBlock.Link -> HelpLink(block)
                }
            }
        }
    }
}

@Composable
private fun HelpParagraph(text: String, modifier: Modifier = Modifier, code: Boolean = false, link: HelpInlineLink? = null) {
    val label = link?.let { stringResource(it.label) }
    val start = label?.let { text.indexOf(it) } ?: -1
    val linkColor = MaterialTheme.colorScheme.primary
    val content = buildAnnotatedString {
        if (link != null && label != null && start >= 0 && text.indexOf(label, start + label.length) < 0) {
            append(text.substring(0, start))
            withLink(LinkAnnotation.Url(link.url, TextLinkStyles(SpanStyle(color = linkColor,
                textDecoration = TextDecoration.Underline)))) { append(label) }
            append(text.substring(start + label.length))
        } else append(text)
    }
    Text(content, modifier, color = LocalVpnColors.current.foreground, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp).let {
        if (code) it.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr) else it
    })
}

@Composable
private fun helpText(value: HelpText): String = when (value) {
    is HelpText.Resource -> stringResource(value.id)
    is HelpText.Literal -> value.value
}

@Composable
private fun HelpComparison(block: HelpBlock.Comparison) {
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.title?.let { Text(stringResource(it), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif), color = LocalVpnColors.current.foreground) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val vertical = helpUsesVerticalRows(maxWidth.value, fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.rows.forEach { row ->
                    Surface(Modifier.fillMaxWidth(), color = LocalVpnColors.current.panel,
                        border = BorderStroke(1.dp, LocalVpnColors.current.border), shape = MaterialTheme.shapes.small) {
                        if (vertical) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HelpParagraph(helpText(row.label), code = row.code)
                            HelpParagraph(helpText(row.detail))
                        } else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top) {
                            HelpParagraph(helpText(row.label), Modifier.weight(1f), code = row.code)
                            HelpParagraph(helpText(row.detail), Modifier.weight(2f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpProtocolChips(labels: List<String>) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            Surface(shape = MaterialTheme.shapes.small, color = LocalVpnColors.current.panel,
                border = BorderStroke(1.dp, LocalVpnColors.current.border)) {
                HelpParagraph(label, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), code = true)
            }
        }
    }
}

@Composable
private fun HelpDetails(block: HelpBlock.Details, scope: String) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.help_expanded else R.string.help_collapsed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(colors = vpnNeutralOutlinedButtonColors(), onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = state }, contentPadding = PaddingValues(12.dp)) {
            Text(stringResource(block.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif), maxLines = 2)
            Text(if (expanded) "−" else "+", Modifier.clearAndSetSemantics {})
        }
        AnimatedVisibility(expanded) { VpnHelpBlocks(block.blocks, scope = "$scope/${block.id}") }
    }
}

@Composable
private fun HelpLink(block: HelpBlock.Link) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    TextButton(colors = vpnNeutralTextButtonColors(), onClick = {
        runCatching {
            when (block.destination) {
                HelpDestination.INSTALLATION -> uri.openUri("https://github.com/foxhole-team/foxhole-openwrt-client")
                HelpDestination.ANDROID_VPN_SETTINGS -> context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            }
        }
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(block.label), maxLines = 2)
    }
}
