package com.foxhole.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.core.content.getSystemService

internal fun readProfileClipboard(context: Context): String? = try {
    readProfileClipboardContent(context)
} catch (_: RuntimeException) { null }

private fun readProfileClipboardContent(context: Context): String? {
    val clip = context.getSystemService<ClipboardManager>()?.primaryClip ?: return null
    return profileClipboardText(clip)
}

internal fun profileClipboardText(clip: ClipData): String? {
    if (clip.itemCount !in 1..256) return null
    val parts = ArrayList<String>(clip.itemCount)
    var bytes = 0
    for (index in 0 until clip.itemCount) {
        val item = clip.getItemAt(index)
        val text = item.text?.toString() ?: item.uri?.toString() ?: return null
        if (text.length > MaximumClipboardBytes) return null
        bytes += text.toByteArray(Charsets.UTF_8).size + if (index == 0) 0 else 1
        if (bytes > MaximumClipboardBytes) return null
        parts += text
    }
    return parts.joinToString("\n").takeIf(String::isNotBlank)
}

internal fun copyProfileClipboard(context: Context, text: String) {
    require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MaximumClipboardBytes)
    val clipboard = requireNotNull(context.getSystemService<ClipboardManager>())
    clipboard.setPrimaryClip(ClipData.newPlainText("FoxHole VPN profile", text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

private const val MaximumClipboardBytes = 1024 * 1024
