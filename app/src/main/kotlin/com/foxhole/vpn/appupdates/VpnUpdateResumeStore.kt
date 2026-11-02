package com.foxhole.vpn.appupdates

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal class VpnUpdateResumeStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "app-update-resume.v1"))

    @Synchronized
    fun arm(ticket: VpnUpdateResumeTicket?): Boolean {
        if (!cancel()) return false
        if (ticket == null) return true
        return try {
            require(ticket.profileId.length in 1..160 && ticket.configurationDigest.length == 64 &&
                ticket.targetVersionCode > 0 && ticket.createdAtMillis > 0)
            val bytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { output ->
                    output.writeInt(1)
                    output.writeUTF(ticket.profileId)
                    output.writeUTF(ticket.configurationDigest)
                    output.writeLong(ticket.targetVersionCode)
                    output.writeLong(ticket.createdAtMillis)
                }
            }.toByteArray()
            require(bytes.size <= 1024)
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (failure: Exception) { file.failWrite(stream); throw failure }
            true
        } catch (_: Exception) { cancel(); false }
    }

    @Synchronized
    fun read(): VpnUpdateResumeTicket? = try {
        val backup = File(file.baseFile.path + ".bak")
        val source = if (backup.exists()) backup else file.baseFile
        require(source.isFile && source.length() in 1..1024)
        val bytes = file.readFully()
        require(bytes.size in 1..1024)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val ticket = VpnUpdateResumeTicket(input.readUTF(), input.readUTF(), input.readLong(), input.readLong())
            require(input.available() == 0 && ticket.profileId.length in 1..160 && ticket.configurationDigest.length == 64)
            ticket
        }
    } catch (_: Exception) { cancel(); null }

    @Synchronized
    fun consume(ticket: VpnUpdateResumeTicket): Boolean = read() == ticket && cancel()

    @Synchronized
    fun cancel(): Boolean = try {
        // Stop removes the tiny handoff before an installer can terminate this process.
        file.delete()
        !file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists()
    } catch (_: RuntimeException) { false }
}
