package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import java.security.MessageDigest
import org.json.JSONObject

class V7Phase1ReceiverTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun packagedManifestIsCanonicalPhase1Artifact() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("v7_phy_selection/phase1_manifest.json"))
            .use { it.readBytes() }
        val normalizedBytes = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(normalizedBytes).joinToString("") { "%02X".format(it) }
        val json = JSONObject(String(bytes, Charsets.UTF_8))

        assertEquals("E2D7924BD90ADD3F07CE9C0580356BACD231E4B56834103A0E8516B96C054200", hash)
        assertEquals("LAB_ONLY_NOT_A_V7_WIRE_CONTRACT", json.getString("status"))
        assertEquals(5, json.getJSONArray("grid_profiles").length())
        assertEquals(2, json.getJSONArray("qr_controls").length())
    }

    @Test
    fun rectangularMonochromeFrameDecodesAgainstContinuousTruth() {
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val frameIndex = 37
        val luma = render(profile, receiver.expectedSymbols(frameIndex), frameIndex)

        val result = receiver.analyze(identity, luma, 1000, 1000)

        assertEquals(frameIndex, result.frameIndex)
        assertEquals(3200, result.observedBits)
        assertEquals(0, result.bitErrors)
        assertEquals(0, result.erasedBits)
        assertTrue(result.frameValid)
        assertTrue(result.postFecValid)
    }

    @Test
    fun repeatedIndexSurvivesOneCorruptCopyAndBitErrorIsExact() {
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val frameIndex = 173
        val expected = receiver.expectedSymbols(frameIndex)
        val luma = render(profile, expected, frameIndex)

        // Corrupt one complete copy of the index. The other two must win.
        fillRect(luma, 1000, 280, 145, 427, 175, 128)
        // Flip one payload cell far from edges/probes.
        val cellW = 800.0 / profile.cols
        val cellH = 620.0 / profile.rows
        val cell = 10 * profile.cols + 10
        val value = if (expected[cell].toInt() == 0) 235 else 20
        fillCell(luma, 1000, profile, 10, 10, cellW, cellH, value)

        val result = receiver.analyze(identity, luma, 1000, 1000)

        assertEquals(frameIndex, result.frameIndex)
        assertEquals(1, result.bitErrors)
        assertEquals(0, result.erasedBits)
        assertEquals(1, result.byteErrors)
        assertTrue(result.postFecValid)
    }

    @Test
    fun concentratedDamageUsesRealRs255BlockBoundary() {
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val frameIndex = 11
        val expected = receiver.expectedSymbols(frameIndex)
        val luma = render(profile, expected, frameIndex)
        val cellW = 800.0 / profile.cols
        val cellH = 620.0 / profile.rows
        // 16 byte errors in the first 200-byte block cost 32 parity symbols;
        // the balanced 15% model gives that block 30, so the frame must fail.
        for (byte in 0 until 16) {
            val cell = byte * 8
            val row = cell / profile.cols
            val col = cell % profile.cols
            fillCell(luma, 1000, profile, row, col, cellW, cellH, if (expected[cell].toInt() == 0) 235 else 20)
        }

        val result = receiver.analyze(identity, luma, 1000, 1000)
        assertEquals(16, result.byteErrors)
        assertTrue(!result.postFecValid)
    }

    @Test
    fun qrPayloadValidationRequiresBinaryLengthHeaderAndCrc() {
        val payload = qrPayload(version = 27, frameIndex = 91, frameBytes = 1465)
        val good = V7Phase1QrDecoder.validatePayload(payload, 27, 1465)
        assertTrue(good.valid)
        assertEquals(91L, good.frameIndex)

        payload[100] = (payload[100].toInt() xor 1).toByte()
        val bad = V7Phase1QrDecoder.validatePayload(payload, 27, 1465)
        assertEquals("crc", bad.failure)
    }

    private fun render(profile: V7Phase1GridProfile, symbols: ByteArray, frameIndex: Int): ByteArray {
        val image = ByteArray(1000 * 1000) { 235.toByte() }
        fillRect(image, 1000, 290, 110, 310, 130, 20)
        fillRect(image, 1000, 370, 110, 390, 130, 235)
        val cellW = 800.0 / profile.cols
        val cellH = 620.0 / profile.rows
        for (row in 0 until profile.rows) for (col in 0 until profile.cols) {
            val symbol = symbols[row * profile.cols + col].toInt()
            fillCell(image, 1000, profile, row, col, cellW, cellH, if (symbol == 0) 20 else 235)
        }
        val bits = IntArray(8) { bit -> (frameIndex shr (7 - bit)) and 1 }
        val stripCellW = 440.0 / 24.0
        for (repeat in 0 until 3) for (bit in 0 until 8) {
            val cell = repeat * 8 + bit
            fillRect(
                image, 1000,
                (280.0 + cell * stripCellW).toInt(), 145,
                (280.0 + (cell + 1) * stripCellW).toInt(), 175,
                if (bits[bit] == 0) 20 else 235,
            )
        }
        return image
    }

    private fun fillCell(
        image: ByteArray, width: Int, profile: V7Phase1GridProfile,
        row: Int, col: Int, cellW: Double, cellH: Double, value: Int,
    ) = fillRect(
        image, width,
        (100.0 + col * cellW).toInt(), (190.0 + row * cellH).toInt(),
        (100.0 + (col + 1) * cellW).toInt(), (190.0 + (row + 1) * cellH).toInt(), value,
    )

    private fun fillRect(
        image: ByteArray, width: Int, left: Int, top: Int, right: Int, bottom: Int, value: Int,
    ) {
        for (y in top until bottom) for (x in left until right) image[y * width + x] = value.toByte()
    }

    private fun qrPayload(version: Int, frameIndex: Int, frameBytes: Int): ByteArray {
        val payload = ByteArray(frameBytes)
        payload[0] = 'S'.code.toByte(); payload[1] = 'Q'.code.toByte()
        payload[2] = 'P'.code.toByte(); payload[3] = '1'.code.toByte()
        payload[4] = version.toByte(); payload[5] = 1
        writeLe32(payload, 6, frameIndex)
        writeLe32(payload, 10, 42)
        payload[14] = (frameBytes and 0xFF).toByte()
        payload[15] = ((frameBytes ushr 8) and 0xFF).toByte()
        val prng = V7LabPrng((42 xor version xor frameIndex).let { if (it == 0) 1 else it })
        for (index in 16 until frameBytes - 4) payload[index] = (prng.next() and 0xFF).toByte()
        writeLe32(payload, frameBytes - 4, CRC32().apply { update(payload, 0, frameBytes - 4) }.value.toInt())
        return payload
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (shift in 0 until 4) bytes[offset + shift] = ((value ushr (8 * shift)) and 0xFF).toByte()
    }
}
