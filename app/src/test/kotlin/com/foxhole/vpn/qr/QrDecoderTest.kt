package com.foxhole.vpn.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test fun cameraDecoderReadsTheSyntheticQrInEachQuarterTurn() {
        val payload = "vless://00000000-0000-4000-8000-000000000001@owned.invalid:443?security=tls"
        var pixels = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256).pixels()
        repeat(4) {
            val decoder = qrOnlyDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>())
            val result = decoder.decode(RGBLuminanceSource(256, 256, pixels))
            assertEquals(payload, result?.text)
            val session = QrScanSession().apply { setPermission(true); setResumed(true) }
            assertEquals(payload, session.claim(requireNotNull(session.token), result?.text))
            val previous = pixels
            pixels = IntArray(256 * 256) { index -> previous[(255 - index % 256) * 256 + index / 256] }
        }
    }

    @Test fun cameraDecoderRejectsBlankFramesAndNonQrBarcodes() {
        val decoder = qrOnlyDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>())
        assertNull(decoder.decode(RGBLuminanceSource(256, 256, IntArray(256 * 256) { -1 })))
        val barcode = Code128Writer().encode("owned123", BarcodeFormat.CODE_128, 320, 160)
        assertNull(decoder.decode(RGBLuminanceSource(barcode.width, barcode.height, barcode.pixels())))
    }

    private fun BitMatrix.pixels(): IntArray = IntArray(width * height) { index -> if (get(index % width, index / width)) -16777216 else -1 }
}
