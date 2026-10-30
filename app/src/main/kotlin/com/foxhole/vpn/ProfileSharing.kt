package com.foxhole.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent

internal fun shareVpnProfile(context: Context, text: String) {
    require(text.isNotBlank() && text.length <= MaximumSharedProfileBytes)
    require(text.toByteArray(Charsets.UTF_8).size <= MaximumSharedProfileBytes)
    val content = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(content, context.getString(R.string.vpn_profile_share))
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

// Reserve Binder space for chooser metadata.
private const val MaximumSharedProfileBytes = 256 * 1024
