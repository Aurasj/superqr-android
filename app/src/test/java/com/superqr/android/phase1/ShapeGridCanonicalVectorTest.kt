package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.CRC32

class ShapeGridCanonicalVectorTest {
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
    fun `android fast block zero matches protocol canonical vector`() {
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 19, 0xBEEF, 0, 256, 3)
        val data = blockData(fast, 0, envelope)
        assertEquals(
            "c57adee57b2e22c0093d161e1a2c16c94f4782732ec77e561674b69e2a03d9df",
            sha256(data),
        )

        val encoded = ByteArray(1275)
        repeat(5) { shard ->
            ShapeGridReedSolomon.encode(data.copyOfRange(shard * 207, (shard + 1) * 207))
                .copyInto(encoded, shard * 255)
        }
        assertEquals(
            "780bbe22fb3c1e998d87884cf9f90b02f5e6a4e61199303fd133b4a95688f121",
            sha256(encoded),
        )

        val symbols = bytesToSymbols(encoded)
        assertEquals(
            "fee86b37f167825a5e2e137bcd5b3177f4d15466e49b72f4bb1843e8bcbcaf2f",
            sha256(ByteArray(symbols.size) { symbols[it].toByte() }),
        )

        val cells = ByteArray(fast.blockCells) { 0xFF.toByte() }
        symbols.forEachIndexed { logical, symbol -> cells[(73 * logical) % fast.blockCells] = symbol.toByte() }
        assertEquals(
            "631f141b4bc3e817419b14d00827aa4aec7be43a9464e8038c37657c62247a44",
            sha256(cells),
        )
    }

    private fun blockData(profile: ShapeGridProfile, blockId: Int, envelope: V7LabRunEnvelope): ByteArray {
        val data = ByteArray(profile.rsCodewordsPerBlock * 207)
        data[0] = 'S'.code.toByte(); data[1] = 'Q'.code.toByte(); data[2] = 'S'.code.toByte(); data[3] = '1'.code.toByte()
        data[4] = 1; data[5] = blockId.toByte(); data[6] = 8; data[7] = 0
        envelope.encode().copyInto(data, 8)
        put32(data, 18, 42)
        put16(data, 22, profile.usefulBytesPerBlock)
        data[24] = 4; data[25] = 2
        var state = (42 xor (profile.id shl 16) xor (blockId shl 8) xor envelope.frameIndex).toUInt()
        if (state == 0u) state = 1u
        repeat(profile.usefulBytesPerBlock) { index ->
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            data[26 + index] = (state and 0xFFu).toByte()
        }
        val crcOffset = 26 + profile.usefulBytesPerBlock
        put32(data, crcOffset, CRC32().apply { update(data, 0, crcOffset) }.value.toInt())
        return data
    }

    private fun bytesToSymbols(data: ByteArray): IntArray {
        val output = IntArray(data.size * 8 / 6)
        var accumulator = 0L
        var bits = 0
        var cursor = 0
        for (byte in data) {
            accumulator = (accumulator shl 8) or (byte.toLong() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                output[cursor++] = ((accumulator ushr bits) and 0x3F).toInt()
                accumulator = if (bits == 0) 0 else accumulator and ((1L shl bits) - 1)
            }
        }
        return output
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte(); bytes[offset + 3] = (value ushr 24).toByte()
    }
}
