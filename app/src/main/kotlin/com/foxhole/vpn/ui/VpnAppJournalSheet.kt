package com.foxhole.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.vpn.R
import com.foxhole.vpn.journal.AppJournal
import com.foxhole.vpn.journal.AppJournalResult
import com.foxhole.vpn.journal.AppJournalRetention
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun VpnAppJournalSheet(journal: AppJournal, onBack: () -> Unit, onSave: () -> Unit, saving: Boolean = false,
    onSettings: () -> Unit = {}) {
    val state by journal.state.collectAsStateWithLifecycle()
    LaunchedEffect(journal) { journal.load() }
    VpnRouterSheetContent(R.string.vpn_app_journal_title, R.drawable.lin_file, scrollable = false,
        naturalHeight = true,
        actions = {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.vpn_app_journal_settings))
            }
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.router_review_back))
                }
                Button(onClick = onSave, enabled = state.loaded && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.vpn_app_journal_save))
                }
            }
        }) {
        if (state.storageError) Text(stringResource(R.string.vpn_app_journal_storage_error),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val colors = LocalVpnColors.current
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            color = colors.background, contentColor = colors.foreground, border = BorderStroke(1.dp, colors.border)) {
            if (!state.loaded) Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else if (state.entries.isEmpty()) Text(stringResource(R.string.vpn_app_journal_empty),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            else LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    JournalTableRow(stringResource(R.string.vpn_app_journal_time), stringResource(R.string.vpn_app_journal_event),
                        stringResource(R.string.vpn_app_journal_result), header = true)
                    HorizontalDivider(color = colors.border)
                }
                itemsIndexed(state.entries) { index, entry ->
                    if (index > 0) HorizontalDivider(color = colors.border.copy(alpha = .6f))
                    SelectionContainer {
                        JournalTableRow(journalTime.format(Instant.ofEpochMilli(entry.timeMillis)),
                            entry.event.name.replace('_', ' '),
                            if (entry.result == AppJournalResult.NONE) "—" else entry.result.name)
                    }
                }
            }
        }
    }
}

private val journalTime = DateTimeFormatter.ofPattern("uuuu-MM-dd\nHH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC)

@Composable
private fun JournalTableRow(time: String, event: String, result: String, header: Boolean = false) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
        fontSize = 11.sp, lineHeight = 15.sp, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal)
    val color = if (header) LocalVpnColors.current.muted else LocalVpnColors.current.foreground
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(time, Modifier.weight(.34f), style = style, color = color)
        Text(event, Modifier.weight(.42f), style = style, color = color)
        Text(result, Modifier.weight(.24f), style = style, color = color)
    }
}

@Composable
internal fun VpnAppJournalSettingsSheet(journal: AppJournal) {
    val state by journal.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var changingRetention by remember { mutableStateOf(false) }
    LaunchedEffect(journal) { journal.load() }
    VpnSheetContent(R.string.vpn_app_journal_settings, R.drawable.lin_settings,
        Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vpn_app_journal_retention), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            VpnChoiceDropdown(stringResource(journalRetentionLabel(state.retention)), state.loaded && !changingRetention) { dismiss ->
                AppJournalRetention.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(stringResource(journalRetentionLabel(value)), maxLines = 1,
                        overflow = TextOverflow.Ellipsis) }, onClick = {
                        dismiss()
                        scope.launch {
                            changingRetention = true
                            try { journal.setRetention(value) } finally { changingRetention = false }
                        }
                    })
                }
            }
        }
        if (state.storageError) Text(stringResource(R.string.vpn_app_journal_storage_error),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private fun journalRetentionLabel(value: AppJournalRetention): Int = when (value) {
    AppJournalRetention.DAY -> R.string.vpn_app_journal_day
    AppJournalRetention.WEEK -> R.string.vpn_app_journal_week
    AppJournalRetention.MONTH -> R.string.vpn_app_journal_month
    AppJournalRetention.FOREVER -> R.string.vpn_app_journal_forever
}
