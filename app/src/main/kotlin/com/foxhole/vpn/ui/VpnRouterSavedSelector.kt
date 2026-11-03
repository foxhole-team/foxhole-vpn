package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.router.RouterSavedStatus
import com.foxhole.vpn.router.RouterSavedSummary

@Composable
internal fun VpnRouterSavedSelector(routers: List<RouterSavedSummary>, selectedId: String?,
    enabled: Boolean, showIllustration: Boolean, onSelect: (String) -> Unit) {
    val selected = routers.singleOrNull { it.id == selectedId }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VpnChoiceDropdown(selected?.let { savedRouterName(it) } ?: stringResource(R.string.router_review_choose_router),
            enabled, leadingIcon = R.drawable.lin_router, menuMaxHeight = 280.dp) { dismiss ->
            routers.forEach { router ->
                DropdownMenuItem(text = {
                    val availability = stringResource(when (router.status) {
                            RouterSavedStatus.AVAILABLE -> R.string.router_review_saved_available
                            RouterSavedStatus.UNAVAILABLE -> R.string.router_review_saved_unavailable
                            RouterSavedStatus.UNKNOWN -> R.string.router_review_saved_unknown
                        })
                    Text("${savedRouterName(router)} · $availability", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, leadingIcon = { Icon(painterResource(R.drawable.lin_router), null, Modifier.size(22.dp)) },
                    onClick = { dismiss(); onSelect(router.id) })
            }
        }
        if (showIllustration) VpnRouterIllustration(Modifier.fillMaxWidth().height(128.dp).testTag("router-management-vector"))
    }
}

@Composable
private fun savedRouterName(router: RouterSavedSummary): String = router.label?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.router_review_saved_number, router.number)
