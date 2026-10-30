package com.foxhole.vpn.flags

internal data class CountryFlagSizePx(val width: Float, val height: Float)

internal fun countryFlagSizePx(capitalHeightPx: Float, imageWidth: Int, imageHeight: Int): CountryFlagSizePx? {
    if (!capitalHeightPx.isFinite() || capitalHeightPx <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
    return CountryFlagSizePx(capitalHeightPx * imageWidth / imageHeight, capitalHeightPx)
}
