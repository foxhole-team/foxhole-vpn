package com.foxhole.vpn.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatSubscriptionExpiry(epochMillis: Long?, locale: Locale, zone: ZoneId): String? {
    if (epochMillis == null || epochMillis !in 1..253402300799999L) return null
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
        .format(Instant.ofEpochMilli(epochMillis))
}
