package com.foxhole.vpn

import android.content.Context
import java.io.File

internal class VpnLicenseFileStore(file: File) : VpnLicenseStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "vpn-license-agreement"))

    private val record = VpnVersionFileStore(file)

    override suspend fun readAcceptedVersion(): Int = record.readVersion()

    override suspend fun writeAcceptedVersion(version: Int) {
        require(version == VPN_LICENSE_AGREEMENT_VERSION) { "license_version_unsupported" }
        record.writeVersion(version)
    }
}
