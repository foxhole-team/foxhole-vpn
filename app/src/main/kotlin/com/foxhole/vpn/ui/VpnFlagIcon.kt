package com.foxhole.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.foxhole.vpn.R
import com.foxhole.vpn.flags.countryFlagBitmap
import com.foxhole.vpn.flags.flagCountryCode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun VpnFlagIcon(
    countryCode: String?,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier,
    showUnknown: Boolean = false,
) {
    val code = flagCountryCode(countryCode)
    val slot = vpnFlagIconSize(style)
    if (slot == DpSize.Zero) return
    val resource = when (code) {
        "gb" -> R.drawable.flag_svg_gb
        "ru" -> R.drawable.flag_svg_ru
        "de" -> R.drawable.flag_svg_de
        "fr" -> R.drawable.flag_svg_fr
        "es" -> R.drawable.flag_svg_es
        "jp" -> R.drawable.flag_svg_jp
        "cn" -> R.drawable.flag_svg_cn
        else -> null
    }
    val description = code?.uppercase(Locale.ROOT)
    if (resource != null) {
        Image(painterResource(resource), description, modifier.size(slot))
        return
    }
    val context = LocalContext.current
    val bitmap = remember(code, context) { countryFlagBitmap(context, code)?.asImageBitmap() }
    if (bitmap != null) {
        Canvas(modifier.size(slot).semantics { contentDescription = description.orEmpty() }) {
            drawImage(bitmap, dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High)
        }
    } else if (showUnknown) {
        Box(modifier.size(slot), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.router_review_globe), null, Modifier.size(slot.height),
                tint = LocalVpnColors.current.muted)
        }
    }
}
