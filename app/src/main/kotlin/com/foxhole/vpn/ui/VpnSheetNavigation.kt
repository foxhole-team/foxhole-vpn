package com.foxhole.vpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.foxhole.vpn.VpnProtocolChangeRequest

internal sealed interface VpnSheetPage {
    data object Settings : VpnSheetPage
    data object AppUpdate : VpnSheetPage
    data object RouterEntry : VpnSheetPage
    data object RouterWizard : VpnSheetPage
    data object RouterControl : VpnSheetPage
    data object Profiles : VpnSheetPage
    data class ProfileActions(val profileId: String) : VpnSheetPage
    data object ImportSources : VpnSheetPage
    data object ImportText : VpnSheetPage
    data object InsecureConsent : VpnSheetPage
    data class SettingsDetail(val detail: VpnSettingsDetail) : VpnSheetPage
    data class ProtocolConfirmation(val request: VpnProtocolChangeRequest) : VpnSheetPage
    data class RemoveConfirmation(val profileId: String, val profileRevision: Long) : VpnSheetPage
}

internal data class VpnSheetEntry(val id: Long, val depth: Int, val page: VpnSheetPage)

internal class VpnSheetNavigation {
    private var nextId = 0L
    private var entries by mutableStateOf(emptyList<VpnSheetEntry>())
    val current: VpnSheetEntry? get() = entries.lastOrNull()

    fun open(page: VpnSheetPage) { entries = listOf(VpnSheetEntry(++nextId, 0, page)) }
    fun push(page: VpnSheetPage) { entries = entries + VpnSheetEntry(++nextId, entries.size, page) }
    fun replace(page: VpnSheetPage) {
        val entry = current
        if (entry == null) open(page)
        else entries = entries.dropLast(1) + VpnSheetEntry(++nextId, entry.depth, page)
    }
    fun back(expectedEntryId: Long): Boolean {
        if (current?.id != expectedEntryId) return false
        entries = entries.dropLast(1)
        return true
    }
    fun close(expectedEntryId: Long) {
        if (current?.id == expectedEntryId) entries = emptyList()
    }
}

@Composable
internal fun VpnSheetPageTransition(entry: VpnSheetEntry, animateSize: Boolean = true, content: @Composable (VpnSheetEntry) -> Unit) {
    AnimatedContent(
        targetState = entry,
        modifier = Modifier.fillMaxWidth(),
        contentKey = { it.page },
        transitionSpec = { VpnSheetMotion.pageTransition(animateSize) },
        label = "vpn-sheet-navigation",
        content = { current -> content(current) },
    )
}
