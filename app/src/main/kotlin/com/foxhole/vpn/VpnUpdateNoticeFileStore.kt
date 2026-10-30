package com.foxhole.vpn

import android.content.Context
import java.io.File

internal class VpnUpdateNoticeFileStore(file: File) : VpnUpdateNoticeStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "vpn-update-notice"))

    private val record = VpnVersionFileStore(file)

    override suspend fun readAcknowledgedRevision(): Int = record.readVersion()

    override suspend fun writeAcknowledgedRevision(revision: Int) {
        require(revision == VPN_UPDATE_NOTICE_REVISION) { "notice_revision_unsupported" }
        record.writeVersion(revision)
    }
}
