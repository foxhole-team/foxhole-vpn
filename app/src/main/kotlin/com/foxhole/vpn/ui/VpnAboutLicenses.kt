package com.foxhole.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.vpn.BuildConfig
import com.foxhole.vpn.R

@Composable
internal fun VpnAboutContent(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(stringResource(R.string.vpn_about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.vpn_licenses_title), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        COMPONENT_LICENSES.forEach { (component, license) ->
            ComponentLicenseRow(component, license)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ComponentLicenseRow(component: String, license: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(component, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(license, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val COMPONENT_LICENSES = listOf(
    "FoxHole VPN" to "GPL-3.0-or-later",
    "FoxHole Core" to "GPL-3.0-or-later",
    "Rust standard library" to "MIT OR Apache-2.0",
    "rustls" to "Apache-2.0 OR ISC OR MIT",
    "AndroidX / Jetpack Compose" to "Apache-2.0",
    "AndroidX graphics-path" to "Apache-2.0",
    "Kotlin / kotlinx" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "JSch" to "BSD-3-Clause AND ISC",
    "ZXing Android Embedded" to "Apache-2.0",
    "Tiny5" to "OFL-1.1",
    "JetBrains Mono" to "OFL-1.1",
    "Tabler Icons" to "MIT",
    "flag-icons" to "MIT",
)
