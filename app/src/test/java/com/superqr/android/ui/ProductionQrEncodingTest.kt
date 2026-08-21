package com.superqr.android.ui

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.superqr.android.transfer.ProductionQrContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionQrEncodingTest {
    @Test
    fun fullProductionFramesFitTheirQrProfiles() {
        for (profile in ProductionQrContract.profiles) {
            val frame = ByteArray(profile.frameBytes) { index -> (index * 73 + 41).toByte() }
            val matrix = encodeProductionQr(frame, profile)

            assertEquals(185, matrix.width)
            assertEquals(185, matrix.height)
            val modules = ProductionQrContract.QR_VERSION * 4 + 17
            val symbol = BitMatrix(modules)
            for (y in 0 until modules) for (x in 0 until modules) {
                if (matrix[x + 4, y + 4]) symbol.set(x, y)
            }
            val decoded = Decoder().decode(symbol)
            assertArrayEquals(frame, decoded.byteSegments.single())
        }
    }
}
