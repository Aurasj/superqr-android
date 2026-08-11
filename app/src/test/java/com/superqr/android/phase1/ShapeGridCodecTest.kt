package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class ShapeGridCodecTest {
    private val fast = ShapeGridProfile(
        id = 19,
        name = "shapegrid_c4_s16_136x100_fast20",
        role = "DEFAULT_FAST",
        targetFps = 20.0,
        gridCols = 136,
        gridRows = 100,
        blockCols = 34,
        blockRows = 50,
        blockCells = 1700,
        rsCodewordsPerBlock = 5,
        encodedBytesPerBlock = 1275,
        activeSymbolsPerBlock = 1700,
        usefulBytesPerBlock = 1005,
        usefulBytesPerEpoch = 8040,
        theoreticalMbps = 1.2864,
        canonicalGridBbox = doubleArrayOf(92.0, 200.0, 908.0, 800.0),
    )

    @Test
    fun `rs 255 207 repairs mixed errors and erasures`() {
        val data = ByteArray(207) { ((it * 73 + 11) and 0xFF).toByte() }
        val encoded = ShapeGridReedSolomon.encode(data)
        val damaged = encoded.copyOf()
        val erasures = intArrayOf(3, 19, 88, 154, 230)
        erasures.forEach { damaged[it] = 0 }
        val errors = intArrayOf(7, 41, 101, 177, 249)
        errors.forEach { damaged[it] = (damaged[it].toInt() xor 0x5A).toByte() }
        val decoded = ShapeGridReedSolomon.decode(damaged, 48, erasures)
        assertArrayEquals(data, decoded.data)
        assertArrayEquals(encoded, decoded.correctedCodeword)
        assertEquals(5, decoded.erasuresUsed)
        assertEquals(5, decoded.errorsCorrected)
    }

    @Test
    fun `fast block round trips through six bit packing and affine interleaver`() {
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 19, 0xBEEF, 7, 256, 3)
        val sender = senderBlock(fast, blockId = 3, envelope = envelope)
        val decoded = ShapeGridBlockCodec.decode(fast, 3, envelope, sender.physicalSymbols)
        assertTrue(decoded.failure ?: "", decoded.valid)
        assertArrayEquals(sender.payload, decoded.payload)
        assertEquals(0, decoded.rsErrors)
        assertEquals(0, decoded.rsErasures)
        assertEquals(0, decoded.shapeSymbolErrors)
        assertEquals(0, decoded.colorSymbolErrors)
        assertEquals(0, decoded.symbolErasures)
    }

    @Test
    fun `fast block survives optical erasures and reports shape and color corruption`() {
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 19, 0x4567, 9, 256, 3)
        val sender = senderBlock(fast, blockId = 2, envelope = envelope)
        val damaged = sender.physicalSymbols.copyOf()

        // These operate on physical positions after interleaving. Keep the count
        // deliberately well below RS(255,207)'s 48-symbol parity budget.
        val erasePhysical = intArrayOf(11, 221, 517, 911, 1327)
        erasePhysical.forEach { damaged[it] = -1 }

        // Change shape but preserve color bits.
        val shapePhysical = intArrayOf(67, 379, 1463)
        shapePhysical.forEach { index ->
            val value = damaged[index]
            damaged[index] = (((value ushr 2) xor 0x1) shl 2) or (value and 0x03)
        }

        // Change only color bits.
        val colorPhysical = intArrayOf(139, 733, 1579)
        colorPhysical.forEach { index ->
            val value = damaged[index]
            damaged[index] = (value and 0x3C) or ((value + 1) and 0x03)
        }

        val decoded = ShapeGridBlockCodec.decode(fast, 2, envelope, damaged)
        assertTrue(decoded.failure ?: "", decoded.valid)
        assertArrayEquals(sender.payload, decoded.payload)
        assertTrue(decoded.rsErasures > 0)
        assertTrue(decoded.rsErrors > 0)
        assertEquals(erasePhysical.size, decoded.symbolErasures)
        assertTrue(decoded.shapeSymbolErrors >= shapePhysical.size)
        assertTrue(decoded.colorSymbolErrors >= colorPhysical.size)
    }

    @Test
    fun `wrong outer epoch rejects otherwise valid block`() {
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 19, 0x1111, 4, 256, 3)
        val sender = senderBlock(fast, blockId = 0, envelope = envelope)
        val wrong = envelope.copy(frameIndex = 5)
        val decoded = ShapeGridBlockCodec.decode(fast, 0, wrong, sender.physicalSymbols)
        assertFalse(decoded.valid)
        assertEquals(0, decoded.payload.size)
    }

    private data class SenderBlock(val payload: ByteArray, val physicalSymbols: IntArray)

    private fun senderBlock(
        profile: ShapeGridProfile,
        blockId: Int,
        envelope: V7LabRunEnvelope,
    ): SenderBlock {
        val blockData = ByteArray(profile.rsCodewordsPerBlock * 207)
        blockData[0] = 'S'.code.toByte()
        blockData[1] = 'Q'.code.toByte()
        blockData[2] = 'S'.code.toByte()
        blockData[3] = '1'.code.toByte()
        blockData[4] = 1
        blockData[5] = blockId.toByte()
        blockData[6] = 8
        blockData[7] = 0
        envelope.encode().copyInto(blockData, 8)
        put32(blockData, 18, 42)
        put16(blockData, 22, profile.usefulBytesPerBlock)
        blockData[24] = 4
        blockData[25] = 2
        val payload = deterministicPayload(profile.id, blockId, envelope.frameIndex, profile.usefulBytesPerBlock)
        payload.copyInto(blockData, 26)
        val crcOffset = 26 + payload.size
        val crc = CRC32().apply { update(blockData, 0, crcOffset) }.value
        put32(blockData, crcOffset, crc.toInt())

        val encoded = ByteArray(profile.encodedBytesPerBlock)
        repeat(profile.rsCodewordsPerBlock) { shard ->
            ShapeGridReedSolomon.encode(
                blockData.copyOfRange(shard * 207, (shard + 1) * 207)
            ).copyInto(encoded, shard * 255)
        }
        val symbols = bytesToSymbols(encoded)
        assertEquals(profile.activeSymbolsPerBlock, symbols.size)
        val physical = IntArray(profile.blockCells) { -1 }
        val offset = (17 * envelope.frameIndex + 131 * blockId) % profile.blockCells
        symbols.forEachIndexed { logical, symbol ->
            physical[(73 * logical + offset) % profile.blockCells] = symbol
        }
        return SenderBlock(payload, physical)
    }

    private fun deterministicPayload(profileId: Int, blockId: Int, frameIndex: Int, size: Int): ByteArray {
        var state = (42 xor (profileId shl 16) xor (blockId shl 8) xor frameIndex).toUInt()
        if (state == 0u) state = 1u
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            (state and 0xFFu).toByte()
        }
    }

    private fun bytesToSymbols(data: ByteArray): IntArray {
        val out = IntArray(data.size * 8 / 6)
        var accumulator = 0L
        var bits = 0
        var cursor = 0
        data.forEach { byte ->
            accumulator = (accumulator shl 8) or (byte.toLong() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out[cursor++] = ((accumulator ushr bits) and 0x3F).toInt()
                accumulator = if (bits == 0) 0 else accumulator and ((1L shl bits) - 1)
            }
        }
        return out
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
