package com.foxhole.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.R
import com.foxhole.vpn.VPN_LICENSE_AGREEMENT_VERSION
import com.foxhole.vpn.VpnLicenseState

@Composable
internal fun VpnLicenseGate(
    state: VpnLicenseState,
    onAccept: (Boolean) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        !state.loaded -> Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.vpn_license_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        state.accepted -> content()
        else -> VpnLicenseAgreement(state.saving, state.storageError, onAccept, onClose)
    }
}

@Composable
private fun VpnLicenseAgreement(
    saving: Boolean,
    storageError: Boolean,
    onAccept: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var checked by rememberSaveable(VPN_LICENSE_AGREEMENT_VERSION) { mutableStateOf(false) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = licensesOpen) { licensesOpen = false }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            val scrollAgreement = !licensesOpen && maxHeight < 480.dp
            Column(Modifier.fillMaxSize().then(if (scrollAgreement) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(if (licensesOpen) R.string.vpn_about else R.string.vpn_license_title),
                    style = MaterialTheme.typography.titleLarge)
                if (licensesOpen) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        VpnAboutContent()
                    }
                    TextButton(onClick = { licensesOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vpn_close))
                    }
                } else {
                    Column(if (scrollAgreement) Modifier.fillMaxWidth()
                        else Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Image(painterResource(R.drawable.ic_launcher_openwrt), contentDescription = null,
                            modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, LocalVpnColors.current.border), color = LocalVpnColors.current.panel) {
                            Text(stringResource(R.string.vpn_license_body), Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick = { licensesOpen = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.vpn_license_open_licenses))
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(checked, enabled = !saving, role = Role.Checkbox, onValueChange = { checked = it })
                            .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !saving)
                            Text(stringResource(R.string.vpn_license_accept), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (storageError) {
                            Text(stringResource(R.string.vpn_license_storage_error),
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (checked && !saving) onAccept(true) }, enabled = checked && !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if (saving) R.string.vpn_working else R.string.vpn_continue))
                        }
                    }
                }
            }
        }
    }
}
