package com.foxhole.vpn.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import com.foxhole.vpn.flags.capitalLetterHeightPx
import com.foxhole.vpn.flags.countryFlagSizePx

@Composable
internal fun vpnFlagIconSize(style: TextStyle, imageWidth: Int = 4, imageHeight: Int = 3): DpSize {
    val actualStyle = LocalTextStyle.current.merge(style)
    val typeface = LocalFontFamilyResolver.current.resolve(actualStyle.fontFamily,
        actualStyle.fontWeight ?: FontWeight.Normal, actualStyle.fontStyle ?: FontStyle.Normal,
        actualStyle.fontSynthesis ?: FontSynthesis.All).value as Typeface
    val density = LocalDensity.current
    val textSizePx = with(density) { actualStyle.fontSize.toPx() }
    val capHeight = remember(typeface, textSizePx, actualStyle.fontFeatureSettings) {
        capitalLetterHeightPx(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = textSizePx
            fontFeatureSettings = actualStyle.fontFeatureSettings
        })
    }
    val size = countryFlagSizePx(capHeight, imageWidth, imageHeight) ?: return DpSize.Zero
    return with(density) { DpSize(size.width.toDp(), size.height.toDp()) }
}
