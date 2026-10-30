package com.foxhole.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VpnUiState
import com.foxhole.vpn.VpnPhase
import com.foxhole.vpn.updates.VpnDataUpdateState
import com.foxhole.vpn.updates.VpnDataUpdatePhase
import com.foxhole.vpn.updates.VpnDataUpdateError
import com.foxhole.vpn.appupdates.VpnAppUpdateState
import com.foxhole.vpn.appupdates.VpnAppUpdatePhase
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun VpnHome(
    state: VpnUiState,
    onPrimary: () -> Unit,
    onProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    dataUpdate: VpnDataUpdateState = VpnDataUpdateState(),
    onDataUpdate: () -> Unit = {},
    claimConnectionCue: (Long) -> Boolean = { true },
    appUpdate: VpnAppUpdateState = VpnAppUpdateState(),
    onAppUpdate: () -> Unit = {},
) {
    val colors = LocalVpnColors.current
    val device = state.deviceLocation.location
    val exit = validatedExitLocation(state)
    val route = remember(device?.latitude, device?.longitude, exit?.latitude, exit?.longitude) {
        GlobeRoute(
            GlobeCoordinates.from(device?.latitude, device?.longitude),
            GlobeCoordinates.from(exit?.latitude, exit?.longitude),
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight * 1.25f
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val statusHeight = if (state.selectedProfile?.subscriptionExpiresAt != null) 64.dp else 48.dp
                    val width = minOf(maxWidth, (maxHeight - statusHeight).coerceAtLeast(0.dp) * 400f / 410f, 380.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VpnPlanet(route, state.isConnected, device, exit, Modifier.width(width), connectionId = state.connectionId,
                            claimConnectionCue = claimConnectionCue, phase = state.phase)
                        VpnConnectionStatus(state)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        VpnHomeControls(state, onPrimary, onProfiles, dataUpdate, onDataUpdate, appUpdate, onAppUpdate)
                    }
                }
            }
        } else {
            val scrollable = maxHeight < 430.dp || LocalDensity.current.fontScale > 1.35f
            val contentModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(
                Modifier.fillMaxSize().then(contentModifier).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (scrollable) {
                    VpnPlanet(route, state.isConnected, device, exit, Modifier.widthIn(max = 280.dp).fillMaxWidth(), connectionId = state.connectionId, claimConnectionCue = claimConnectionCue, phase = state.phase)
                    VpnConnectionStatus(state)
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val statusHeight = if (state.selectedProfile?.subscriptionExpiresAt != null) 60.dp else 36.dp
                        val width = minOf(maxWidth, (maxHeight - statusHeight).coerceAtLeast(0.dp) * 400f / 410f, 380.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            VpnPlanet(route, state.isConnected, device, exit, Modifier.width(width), connectionId = state.connectionId, claimConnectionCue = claimConnectionCue, phase = state.phase)
                            VpnConnectionStatus(state)
                        }
                    }
                }
                VpnHomeControls(state, onPrimary, onProfiles, dataUpdate, onDataUpdate, appUpdate, onAppUpdate)
            }
        }
    }
}

@Composable
private fun VpnHomeControls(
    state: VpnUiState, onPrimary: () -> Unit, onProfiles: () -> Unit,
    dataUpdate: VpnDataUpdateState, onDataUpdate: () -> Unit,
    appUpdate: VpnAppUpdateState, onAppUpdate: () -> Unit,
) {
    val colors = LocalVpnColors.current
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onProfiles,
        enabled = state.isReady,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.foreground),
    ) {
        Icon(painterResource(R.drawable.lin_profiles), contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.muted)
        Spacer(Modifier.size(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.selectedProfile?.name ?: stringResource(if (state.profiles.isEmpty()) R.string.vpn_profile_none else R.string.vpn_profile_select),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.foreground,
                maxLines = if (state.profiles.isEmpty()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.selectedProfile?.let { profile ->
                VpnProtocolBadge(profile.protocol, Modifier.padding(start = 6.dp).align(Alignment.Top).offset(y = (-5).dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        Icon(
            painterResource(if (state.profiles.isEmpty()) R.drawable.lin_plus else R.drawable.lin_chevron_down),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.muted,
        )
    }
    Spacer(Modifier.height(16.dp))
    val status = stringResource(statusLabel(state.phase))
    Button(
        onClick = onPrimary,
        enabled = primaryActionEnabled(state),
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).semantics { stateDescription = status },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (colors.monochrome) {
                if (primaryAction(state) == VpnPrimaryAction.DISCONNECT) Color(0xFFB5B5B5) else Color.White
            } else if (primaryAction(state) == VpnPrimaryAction.DISCONNECT) StopRed else StartGreen,
            contentColor = if (colors.monochrome) Color.Black else Color.White,
        ),
    ) {
        Icon(painterResource(R.drawable.lin_power), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(stringResource(primaryLabel(state)), style = MaterialTheme.typography.labelLarge)
    }
    if (dataUpdate.phase in setOf(
        VpnDataUpdatePhase.AVAILABLE, VpnDataUpdatePhase.DOWNLOADING, VpnDataUpdatePhase.VERIFYING,
        VpnDataUpdatePhase.UPDATED, VpnDataUpdatePhase.ERROR,
    )) {
        Spacer(Modifier.height(12.dp))
        VpnDataUpdateStatus(dataUpdate, onDataUpdate)
    }
    if (appUpdate.phase in setOf(VpnAppUpdatePhase.AVAILABLE, VpnAppUpdatePhase.DOWNLOADING,
            VpnAppUpdatePhase.VERIFYING, VpnAppUpdatePhase.READY, VpnAppUpdatePhase.ERROR)) {
        Spacer(Modifier.height(12.dp))
        VpnAppUpdateCard(appUpdate, onAppUpdate)
    }
}

@Composable
private fun VpnConnectionStatus(state: VpnUiState) {
    val colors = LocalVpnColors.current
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.ROOT else locales[0]
    val zone = ZoneId.systemDefault()
    val expiresAt = state.selectedProfile?.subscriptionExpiresAt
    val expiry = remember(expiresAt, locale, zone) { formatSubscriptionExpiry(expiresAt, locale, zone) }
    val showStatus = state.isReady || state.canDisconnect || state.phase == VpnPhase.ERROR
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VpnStatusDot(if (state.isConnected) colors.connected else if (colors.monochrome) colors.muted else StopRed)
            Text(
                stringResource(if (showStatus) statusLabel(state.phase) else R.string.vpn_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.isConnected) colors.connected else colors.foreground,
                textAlign = TextAlign.Center,
            )
        }
        if (expiry != null) {
            Text(
                stringResource(R.string.vpn_subscription_until, expiry),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VpnDataUpdateStatus(state: VpnDataUpdateState, onClick: () -> Unit) {
    val title = when (state.phase) {
        VpnDataUpdatePhase.DOWNLOADING -> R.string.vpn_data_update_downloading
        VpnDataUpdatePhase.VERIFYING -> R.string.vpn_data_update_verifying
        VpnDataUpdatePhase.UPDATED -> R.string.vpn_data_update_done
        VpnDataUpdatePhase.ERROR -> R.string.vpn_data_update_failed
        else -> R.string.vpn_data_update_available
    }
    val progress = if (state.phase == VpnDataUpdatePhase.DOWNLOADING && state.totalBytes > 0) {
        (state.downloadedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f)
    } else null
    val detail = when (state.phase) {
        VpnDataUpdatePhase.DOWNLOADING -> if (progress != null) {
            stringResource(R.string.vpn_data_update_percent, (progress * 100).toInt())
        } else stringResource(R.string.vpn_data_update_downloading)
        VpnDataUpdatePhase.VERIFYING -> stringResource(R.string.vpn_data_update_verifying_detail)
        VpnDataUpdatePhase.UPDATED -> stringResource(R.string.vpn_data_update_done_detail)
        VpnDataUpdatePhase.ERROR -> stringResource(when (state.error) {
            VpnDataUpdateError.NETWORK -> R.string.vpn_data_update_error_network
            VpnDataUpdateError.STORAGE -> R.string.vpn_data_update_error_storage
            VpnDataUpdateError.NATIVE -> R.string.vpn_data_update_error_native
            else -> R.string.vpn_data_update_error_verification
        })
        else -> stringResource(R.string.vpn_data_update_available_detail)
    }
    VpnDataUpdateCard(
        title = stringResource(title), subtitle = detail, progress = progress, busy = state.isBusy,
        onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = !state.isBusy && state.phase != VpnDataUpdatePhase.UPDATED,
    )
}

private val StartGreen = Color(0xFF1E6C39)
private val StopRed = Color(0xFFB3261E)

@Composable
internal fun VpnBrand() {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_launcher_openwrt),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
}
