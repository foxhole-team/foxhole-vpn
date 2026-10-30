package com.foxhole.vpn.qr

import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DefaultDecoderFactory

internal fun qrOnlyDecoderFactory() = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
