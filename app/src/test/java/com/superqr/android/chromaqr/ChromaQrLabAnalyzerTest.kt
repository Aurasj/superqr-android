package com.superqr.android.chromaqr

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromaQrLabAnalyzerTest {
    @Test
    fun syntheticPerfectColorFrameHasZeroErrors() {
        val frameIndex = 17
        val seed = 42
        val payload = buildPayload(frameIndex, seed, 20)
        val expected = ChromaQrLabAnalyzer.expectedBits(seed, frameIndex)
        val firstWord = expected.take(32).fold(0L) { acc, bit -> (acc shl 1) or bit.toLong() }
        assertEquals(0xD187247EL, firstWord)

        val reader = ChromaPixelReader { x, y, destination ->
            val col = x.toInt().coerceIn(0, ChromaQrLabAnalyzer.QR_MODULES - 1)
            val row = y.toInt().coerceIn(0, ChromaQrLabAnalyzer.QR_MODULES - 1)
            val index = row * ChromaQrLabAnalyzer.QR_MODULES + col
            val bit = expected[index].toInt()
            destination[0] = if (bit == 0) 128 else if ((index and 1) == 0) 28 else 228
            destination[1] = 128
            true
        }
        val quad = listOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(177.0, 0.0),
            doubleArrayOf(177.0, 177.0),
            doubleArrayOf(0.0, 177.0),
        )
        val result = ChromaQrLabAnalyzer().analyze(payload, quad, reader)
        assertNotNull(result)
        result!!
        assertEquals(frameIndex, result.frameIndex)
        assertEquals(20, result.senderFps)
        assertEquals(0, result.bitErrors)
        assertEquals(0, result.erasedBits)
        assertEquals(ChromaQrLabAnalyzer.TOTAL_BITS, result.goodBits)
        assertTrue(result.ber == 0.0)
        assertTrue(result.rawCombinedKibS > 130.0)
    }

    @Test
    fun invalidMarkerIsRejected() {
        val payload = buildPayload(0, 42, 15)
        payload[26] = 'X'.code.toByte()
        assertEquals(null, ChromaQrLabAnalyzer.parseHeader(payload))
    }

    private fun buildPayload(frameIndex: Int, seed: Int, fps: Int): ByteArray {
        val payload = ByteArray(2953)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        payload[0] = 'S'.code.toByte(); payload[1] = 'Q'.code.toByte()
        payload[2] = 'P'.code.toByte(); payload[3] = '1'.code.toByte()
        payload[4] = 40; payload[5] = 1
        buffer.putInt(6, frameIndex)
        buffer.putInt(10, seed)
        buffer.putShort(14, 2953.toShort())
        payload[26] = 'C'.code.toByte(); payload[27] = 'Q'.code.toByte()
        payload[28] = '4'.code.toByte(); payload[29] = 'B'.code.toByte()
        payload[30] = 1; payload[31] = 1
        buffer.putShort(32, ChromaQrLabAnalyzer.QR_MODULES.toShort())
        payload[34] = fps.toByte()
        val crc = CRC32().apply { update(payload, 0, payload.size - 4) }.value
        buffer.putInt(payload.size - 4, crc.toInt())
        return payload
    }
}
