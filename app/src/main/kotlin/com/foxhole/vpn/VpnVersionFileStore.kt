package com.foxhole.vpn

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class VpnVersionFileStore(file: File) {
    private val record = AtomicFile(file)

    suspend fun readVersion(): Int = withContext(Dispatchers.IO) {
        try {
            DataInputStream(record.openRead()).use { input ->
                val version = input.readInt()
                require(input.read() == -1) { "version_record_invalid" }
                version
            }
        } catch (_: FileNotFoundException) {
            0
        }
    }

    suspend fun writeVersion(version: Int) = withContext(Dispatchers.IO) {
        require(version > 0) { "version_record_invalid" }
        var output: FileOutputStream? = null
        try {
            val stream = record.startWrite()
            output = stream
            DataOutputStream(stream).apply { writeInt(version); flush() }
            stream.fd.sync()
            record.finishWrite(stream)
            output = null
        } catch (failure: Exception) {
            record.failWrite(output)
            throw failure
        }
    }
}
