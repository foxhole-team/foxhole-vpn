package com.foxhole.vpn.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver

internal class VpnHelpState(val expanded: MutableState<Set<String>>, val scroll: ScrollState)

private val HelpExpandedSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

@Composable
internal fun rememberVpnHelpState(): VpnHelpState {
    val expanded = rememberSaveable(stateSaver = HelpExpandedSaver) { mutableStateOf(emptySet<String>()) }
    val scroll = rememberScrollState()
    return remember(expanded, scroll) { VpnHelpState(expanded, scroll) }
}
