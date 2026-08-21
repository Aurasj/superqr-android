package com.superqr.android.vision.lab.colorgrid8

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** LAB-only ColorGrid8 v2 transport framing. Production V7 does not import it. */
object ColorGrid8TransferCodec {
    const val VERSION = 2
    const val KIND_DATA = 0
    const val KIND_XOR_PARITY = 1
    const val XOR_GROUP_SIZE = 8
    const val HEADER_SIZE = 32
    const val BLOCK_DATA_BYTES = 512
    const val BLOCK_CRC_BYTES = 4
    const val MAX_DATA_FRAMES = 2_000_000
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'G'.code.toByte(), '2'.code.toByte())

    data class Frame(
        val kind: Int,
        val profileId: Int,
        val sessionId: Int,
        val frameId: Int,
        val totalDataFrames: Int,
        val chunkCapacity: Int,
        val payload: ByteArray,
        val validBlocks: BooleanArray,
    )

    fun encodedPayloadSize(payloadLength: Int): Int {
        if (payloadLength <= 0) return 0
        val blocks = (payloadLength + BLOCK_DATA_BYTES - 1) / BLOCK_DATA_BYTES
        return payloadLength + blocks * BLOCK_CRC_BYTES
    }

    fun logicalChunkCapacity(profile: ColorGrid8Profile): Int {
        val encodedCapacity = profile.byteCapacity - HEADER_SIZE
        var logical = encodedCapacity
        while (encodedPayloadSize(logical) > encodedCapacity) logical--
        return logical
    }

    fun parse(profile: ColorGrid8Profile, symbols: ByteArray): Frame? {
        if (profile.version != ColorGrid8Spec.TRANSFER_HEADER_VERSION) return null
        val bytes = symbolsToBytes(symbols)
        if (bytes.size < HEADER_SIZE) return null
        for (index in MAGIC.indices) if (bytes[index] != MAGIC[index]) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(4)
        val version = buffer.get().toInt() and 0xFF
        val kind = buffer.get().toInt() and 0xFF
        val profileId = buffer.get().toInt() and 0xFF
        val groupSize = buffer.get().toInt() and 0xFF
        val sessionId = buffer.int
        val frameIdLong = buffer.int.toLong() and 0xFFFFFFFFL
        val totalLong = buffer.int.toLong() and 0xFFFFFFFFL
        val payloadLengthLong = buffer.int.toLong() and 0xFFFFFFFFL
        val chunkCapacityLong = buffer.int.toLong() and 0xFFFFFFFFL
        val declaredCrc = buffer.int.toLong() and 0xFFFFFFFFL

        if (version != VERSION || kind !in setOf(KIND_DATA, KIND_XOR_PARITY)) return null
        if (profileId != profile.profileId || groupSize != XOR_GROUP_SIZE || sessionId == 0) return null
        if (totalLong !in 1L..MAX_DATA_FRAMES.toLong()) return null
        val expectedChunkCapacity = logicalChunkCapacity(profile)
        if (chunkCapacityLong != expectedChunkCapacity.toLong() || expectedChunkCapacity <= 0) return null
        if (payloadLengthLong !in 1L..chunkCapacityLong ||
            HEADER_SIZE + encodedPayloadSize(payloadLengthLong.toInt()) > bytes.size) return null
        if (frameIdLong >= totalLong) return null
        if (kind == KIND_XOR_PARITY && (
                payloadLengthLong != chunkCapacityLong || frameIdLong % XOR_GROUP_SIZE != 0L
            )) return null

        val headerCrc = CRC32().apply { update(bytes, 0, HEADER_SIZE - 4) }.value
        if (headerCrc != declaredCrc) return null
        val payloadLength = payloadLengthLong.toInt()
        val blockCount = (payloadLength + BLOCK_DATA_BYTES - 1) / BLOCK_DATA_BYTES
        val payload = ByteArray(payloadLength)
        val validBlocks = BooleanArray(blockCount)
        var encodedCursor = HEADER_SIZE
        var payloadCursor = 0
        for (block in 0 until blockCount) {
            val blockLength = minOf(BLOCK_DATA_BYTES, payloadLength - payloadCursor)
            bytes.copyInto(payload, payloadCursor, encodedCursor, encodedCursor + blockLength)
            encodedCursor += blockLength
            val blockCrc = ByteBuffer.wrap(bytes, encodedCursor, BLOCK_CRC_BYTES).int.toLong() and 0xFFFFFFFFL
            encodedCursor += BLOCK_CRC_BYTES
            val actualCrc = CRC32().apply { update(payload, payloadCursor, blockLength) }.value
            validBlocks[block] = actualCrc == blockCrc
            payloadCursor += blockLength
        }
        return Frame(
            kind = kind,
            profileId = profileId,
            sessionId = sessionId,
            frameId = frameIdLong.toInt(),
            totalDataFrames = totalLong.toInt(),
            chunkCapacity = expectedChunkCapacity,
            payload = payload,
            validBlocks = validBlocks,
        )
    }

    internal fun symbolsToBytes(symbols: ByteArray): ByteArray {
        val output = ByteArray(symbols.size * ColorGrid8Spec.BITS_PER_CELL / 8)
        var accumulator = 0
        var bitCount = 0
        var outputCursor = 0
        for (symbolByte in symbols) {
            val symbol = symbolByte.toInt() and 0xFF
            if (symbol > 7) return ByteArray(0)
            accumulator = (accumulator shl 3) or symbol
            bitCount += 3
            while (bitCount >= 8) {
                bitCount -= 8
                output[outputCursor++] = (accumulator ushr bitCount).toByte()
                accumulator = if (bitCount == 0) 0 else accumulator and ((1 shl bitCount) - 1)
            }
        }
        return output
    }
}
