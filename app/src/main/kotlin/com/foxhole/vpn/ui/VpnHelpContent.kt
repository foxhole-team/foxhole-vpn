package com.foxhole.vpn.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import java.text.BreakIterator
import java.util.Locale

@Composable
internal fun VpnHelpContent() {
    val scroll = rememberScrollState()
    // Measure collapsed headers without placing them. Expansion changes only
    // the scroll extent, never a remembered measurement or the sheet anchor.
    Layout(content = {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HELP_TOPICS.forEach { topic -> VpnHelpTopicHeader(topic, false, null) }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HELP_TOPICS.forEach { topic -> VpnHelpTopic(topic) }
        }
    }) { children, constraints ->
        val collapsed = children[0].measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val width = constraints.constrainWidth(collapsed.width)
        val height = constraints.constrainHeight(collapsed.height)
        val body = children[1].measure(Constraints.fixed(width, height))
        layout(width, height) { body.placeRelative(0, 0) }
    }
}

@Composable
private fun VpnHelpTopic(topic: VpnHelpTopic) {
    val colors = LocalVpnColors.current
    var expanded by rememberSaveable(topic.title) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = colors.panel, contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.border.copy(alpha = if (expanded) .8f else .4f))) {
        Column {
            VpnHelpTopicHeader(topic, expanded) { expanded = !expanded }
            AnimatedVisibility(expanded,
                enter = expandVertically(VpnSheetMotion.defaultSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(VpnSheetMotion.fastEffectsSpec()),
                exit = shrinkVertically(VpnSheetMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) + fadeOut(VpnSheetMotion.fastEffectsSpec())) {
                VpnHelpBody(stringResource(topic.body), topic.body == R.string.vpn_help_protocols_body,
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun VpnHelpTopicHeader(topic: VpnHelpTopic, expanded: Boolean, onToggle: (() -> Unit)?) {
    val colors = LocalVpnColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(topic.icon), null, Modifier.size(22.dp), tint = colors.muted)
        Text(stringResource(topic.title), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        Icon(painterResource(R.drawable.lin_chevron_down), null,
            Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = colors.muted)
    }
}

@Composable
private fun VpnHelpBody(body: String, protocolList: Boolean, modifier: Modifier) {
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.ROOT else locales[0]
    val paragraphs = remember(body, locale) {
        body.split(Regex("\\n\\s*\\n")).filter(String::isNotBlank).map { vpnHelpSentences(it, locale) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        paragraphs.forEachIndexed { index, sentences ->
            if (index > 0) HorizontalDivider(color = LocalVpnColors.current.border.copy(alpha = .4f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sentences.forEach { sentence ->
                    val enumeration = remember(sentence, protocolList) { vpnHelpEnumeration(sentence, protocolList) }
                    if (enumeration == null) {
                        Text(sentence, style = MaterialTheme.typography.bodyMedium)
                    } else VpnHelpEnumeration(enumeration, sentence)
                }
            }
        }
    }
}

@Composable
private fun VpnHelpEnumeration(list: VpnHelpEnumeration, original: String) {
    val colors = LocalVpnColors.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(original) }) {
        val columns = ((maxWidth.value + 8f) / (80f * fontScale + 8f)).toInt().coerceIn(3, 6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            list.cells.chunked(columns).forEach { cells ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.forEach { cell ->
                        Surface(Modifier.weight(1f).fillMaxHeight().heightIn(min = 40.dp),
                            shape = RoundedCornerShape(10.dp), color = colors.background,
                            contentColor = colors.foreground, border = BorderStroke(1.dp, colors.border.copy(alpha = .4f))) {
                            Text(cell, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal fun vpnHelpSentences(body: String, locale: Locale): List<String> {
    val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(body) }
    val protectedTokens = PROTECTED_HELP_TOKEN.findAll(body).map { it.range }.toList()
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        if (protectedTokens.none { end > it.first && end <= it.last }) {
            body.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(result::add)
            start = end
        }
        end = iterator.next()
    }
    return result
}

internal data class VpnHelpEnumeration(val title: String?, val cells: List<String>)

internal fun vpnHelpEnumeration(sentence: String, allowBareList: Boolean): VpnHelpEnumeration? {
    if (HELP_URI_SCHEME.containsMatchIn(sentence)) return null
    val colon = sentence.indexOfFirst { it == ':' || it == '：' }.takeIf { it in 1..48 }
    if (colon == null && !allowBareList) return null
    val title = colon?.let { sentence.take(it + 1) }
    val content = (colon?.let { sentence.substring(it + 1) } ?: sentence).trim().removeSuffix(".").removeSuffix("。")
    val parts = content.split(',', '、', '，').map(String::trim).filter(String::isNotEmpty)
    if (parts.size < 3) return null
    val cells = mutableListOf<String>()
    parts.forEachIndexed { index, part ->
        if (CONTINUATION.containsMatchIn(part) && cells.isNotEmpty()) cells[cells.lastIndex] += ", $part"
        else if (index == parts.lastIndex) cells += part.replace(LEADING_CONJUNCTION, "").split(FINAL_CONJUNCTION, limit = 2)
        else cells += part
    }
    return VpnHelpEnumeration(title, cells)
}

private val CONTINUATION = Regex("^(?:в том числе|including)(?=\\s|$)", RegexOption.IGNORE_CASE)
private val LEADING_CONJUNCTION = Regex("^(?:и|или|and|or|und|et|y)\\s+", RegexOption.IGNORE_CASE)
private val FINAL_CONJUNCTION = Regex("\\s+(?:и|или|and|or|und|et|y)\\s+", RegexOption.IGNORE_CASE)
private val HELP_URI_SCHEME = Regex("\\b[A-Za-z][A-Za-z0-9+.-]*://")
private val PROTECTED_HELP_TOKEN = Regex("\\b[A-Za-z][A-Za-z0-9+.-]*://\\S*|\\b[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+\\b")

private data class VpnHelpTopic(@StringRes val title: Int, @StringRes val body: Int, @DrawableRes val icon: Int)

private val HELP_TOPICS = listOf(
    VpnHelpTopic(R.string.vpn_help_add_title, R.string.vpn_help_add_body, R.drawable.lin_plus),
    VpnHelpTopic(R.string.vpn_help_connect_title, R.string.vpn_help_connect_body, R.drawable.lin_power),
    VpnHelpTopic(R.string.vpn_help_profiles_title, R.string.vpn_help_profiles_body, R.drawable.lin_profiles),
    VpnHelpTopic(R.string.vpn_help_protocols_title, R.string.vpn_help_protocols_body, R.drawable.lin_file),
    VpnHelpTopic(R.string.vpn_scenarios, R.string.vpn_help_scenarios_body, R.drawable.lin_apps),
    VpnHelpTopic(R.string.vpn_router, R.string.vpn_help_router_body, R.drawable.lin_router),
    VpnHelpTopic(R.string.vpn_help_updates_title, R.string.vpn_help_updates_body, R.drawable.lin_refresh),
    VpnHelpTopic(R.string.vpn_help_privacy_title, R.string.vpn_help_privacy_body, R.drawable.lin_info),
)
