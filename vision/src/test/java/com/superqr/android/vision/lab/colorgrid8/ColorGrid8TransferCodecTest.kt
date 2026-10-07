package com.superqr.android.vision.lab.colorgrid8

import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8TransferCodecTest {
    private val profile = ColorGrid8Profile(
        336,
        288,
        60,
        version = ColorGrid8Spec.TRANSFER_HEADER_VERSION,
    )

    @Test
    fun validFrameRoundTripsFromThreeBitSymbols() {
        val payload = ByteArray(1024) { (it * 37).toByte() }
        val raw = frameBytes(payload)
        val decoded = ColorGrid8TransferCodec.parse(profile, bytesToSymbols(raw))
        requireNotNull(decoded)
        assertEquals(0x12345678, decoded.sessionId)
        assertEquals(3, decoded.frameId)
        assertEquals(10, decoded.totalDataFrames)
        assertArrayEquals(payload, decoded.payload)
        assertTrue(decoded.validBlocks.all { it })
    }

    @Test
    fun payloadCorruptionInvalidatesOnlyItsProtectedBlock() {
        val raw = frameBytes(ByteArray(128) { it.toByte() })
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        val decoded = ColorGrid8TransferCodec.parse(profile, bytesToSymbols(raw))
        requireNotNull(decoded)
        assertFalse(decoded.validBlocks.single())
    }

    @Test
    fun opticalHeaderAloneMustNotReportVerifiedTransport() {
        val headerOnly = ColorGrid8ProcessResult(
            analysis = null, stage = ColorGrid8Stage.PAYLOAD, failure = null,
            quad = emptyList(), acquisitionMode = "GPU_CELL_MEANS", finderCandidates = 4,
            geometryLocked = true, headerStatus = "VALID", expectedProfile = "test", detectedProfile = "test",
        )
        assertFalse(headerOnly.hasVerifiedTransport)
        val raw = frameBytes(ByteArray(128) { it.toByte() })
        val valid = ColorGrid8TransferCodec.parse(profile, bytesToSymbols(raw))
        assertTrue(headerOnly.copy(transportFrame = valid).hasVerifiedTransport)
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        val corrupt = ColorGrid8TransferCodec.parse(profile, bytesToSymbols(raw))
        assertFalse(headerOnly.copy(transportFrame = corrupt).hasVerifiedTransport)
    }

    private fun frameBytes(payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(ColorGrid8TransferCodec.HEADER_SIZE)
            .put(byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'G'.code.toByte(), '2'.code.toByte()))
            .put(ColorGrid8TransferCodec.VERSION.toByte())
            .put(ColorGrid8TransferCodec.KIND_DATA.toByte())
            .put(profile.profileId.toByte())
            .put(ColorGrid8TransferCodec.XOR_GROUP_SIZE.toByte())
            .putInt(0x12345678)
            .putInt(3)
            .putInt(10)
            .putInt(payload.size)
            .putInt(ColorGrid8TransferCodec.logicalChunkCapacity(profile))
            .putInt(0)
            .array()
        val crc = CRC32().apply { update(header, 0, ColorGrid8TransferCodec.HEADER_SIZE - 4) }.value.toInt()
        ByteBuffer.wrap(header).putInt(ColorGrid8TransferCodec.HEADER_SIZE - 4, crc)
        val encodedPayload = ArrayList<Byte>()
        for (offset in payload.indices step ColorGrid8TransferCodec.BLOCK_DATA_BYTES) {
            val end = minOf(offset + ColorGrid8TransferCodec.BLOCK_DATA_BYTES, payload.size)
            val block = payload.copyOfRange(offset, end)
            block.forEach { encodedPayload += it }
            val blockCrc = CRC32().apply { update(block) }.value.toInt()
            ByteBuffer.allocate(4).putInt(blockCrc).array().forEach { encodedPayload += it }
        }
        return header + encodedPayload.toByteArray()
    }

    private fun bytesToSymbols(bytes: ByteArray): ByteArray {
        val symbols = ArrayList<Byte>((bytes.size * 8 + 2) / 3)
        var accumulator = 0
        var bitCount = 0
        for (value in bytes) {
            accumulator = (accumulator shl 8) or (value.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 3) {
                bitCount -= 3
                symbols += ((accumulator ushr bitCount) and 7).toByte()
                accumulator = if (bitCount == 0) 0 else accumulator and ((1 shl bitCount) - 1)
            }
        }
        if (bitCount > 0) symbols += ((accumulator shl (3 - bitCount)) and 7).toByte()
        return symbols.toByteArray()
    }
}
