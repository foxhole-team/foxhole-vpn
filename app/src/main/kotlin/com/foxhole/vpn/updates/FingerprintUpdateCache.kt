package com.foxhole.vpn.updates

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface FingerprintUpdateCache {
    fun read(): FingerprintBundle?
    fun write(bundle: FingerprintBundle)
    fun remove()
}

internal class FileFingerprintUpdateCache(private val file: File) : FingerprintUpdateCache {
    override fun remove() { Files.deleteIfExists(file.toPath()) }

    override fun read(): FingerprintBundle? {
        if (!file.exists()) return null
        require(file.length() in 20..MAX_ENVELOPE_BYTES.toLong()) { "fingerprint cache bounds" }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_ENVELOPE_BYTES) { "fingerprint cache bounds" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1) { "fingerprint cache format" }
            val manifest = input.readPart(MAX_MANIFEST_BYTES)
            val signature = input.readPart(MAX_SIGNATURE_BYTES)
            val document = input.readPart(MAX_DOCUMENT_BYTES)
            require(input.read() == -1) { "fingerprint cache trailing data" }
            FingerprintBundle(manifest, signature, document)
        }
    }

    override fun write(bundle: FingerprintBundle) {
        val parent = requireNotNull(file.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "fingerprint cache directory" }
        val temporary = File.createTempFile("fingerprints-", ".tmp", parent)
        try {
            require(temporary.setReadable(false, false) && temporary.setWritable(false, false) &&
                temporary.setReadable(true, true) && temporary.setWritable(true, true)) { "fingerprint cache permissions" }
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writePart(bundle.manifest, MAX_MANIFEST_BYTES)
                output.writePart(bundle.signature, MAX_SIGNATURE_BYTES)
                output.writePart(bundle.document, MAX_DOCUMENT_BYTES)
                output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun DataInputStream.readPart(limit: Int): ByteArray {
        val size = readInt()
        require(size in 1..limit && size <= available()) { "fingerprint cache part bounds" }
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writePart(bytes: ByteArray, limit: Int) {
        require(bytes.size in 1..limit) { "fingerprint cache part bounds" }
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        const val MAGIC = 0x46565550
        const val MAX_ENVELOPE_BYTES = 20 + MAX_MANIFEST_BYTES + MAX_SIGNATURE_BYTES + MAX_DOCUMENT_BYTES
    }
}
