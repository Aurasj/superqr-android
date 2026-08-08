package com.superqr.android.vision.v7.transport

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * SuperQR V7 baseline transport.
 *
 * Canonical source of truth:
 * superqr-protocol/contracts/v7_transport_contract.json
 */
object V7Transport {
    const val VERSION = 7
    const val FLAGS = 0
    const val GRID_SIZE = 40
    const val CELL_COUNT = 1600
    const val FRAME_SIZE = 400
    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4
    const val PAYLOAD_SIZE = 380
    const val PACKAGE_HEADER_SIZE = 20
    const val MAX_FILENAME_BYTES = 1024
    const val MAX_MIME_BYTES = 255

    private val FRAME_MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte())
    private val PACKAGE_MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte())

    fun buildFrame(sessionId: Int, frameId: Int, totalFrames: Int, payload: ByteArray): ByteArray {
        require(sessionId in 1..0xFFFF)
        require(totalFrames >= 1)
        require(frameId in 0 until totalFrames)
        require(payload.size <= PAYLOAD_SIZE)

        val bytes = ByteArray(FRAME_SIZE)
        val buf = ByteBuffer.wrap(bytes)
        buf.put(FRAME_MAGIC)
        buf.put(VERSION.toByte())
        buf.put(FLAGS.toByte())
        buf.putShort(sessionId.toShort())
        buf.putInt(frameId)
        buf.putInt(totalFrames)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        // Remaining payload bytes are already zero padded by ByteArray initialization.
        val crc = CRC32().apply { update(bytes, 0, FRAME_SIZE - CRC_SIZE) }.value
        ByteBuffer.wrap(bytes, FRAME_SIZE - CRC_SIZE, CRC_SIZE).putInt(crc.toInt())
        return bytes
    }

    fun bytesToSymbols(bytes: ByteArray): ByteArray {
        require(bytes.size == FRAME_SIZE)
        val out = ByteArray(CELL_COUNT)
        var j = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            out[j] = ((b ushr 6) and 0x03).toByte()
            out[j + 1] = ((b ushr 4) and 0x03).toByte()
            out[j + 2] = ((b ushr 2) and 0x03).toByte()
            out[j + 3] = (b and 0x03).toByte()
            j += 4
        }
        return out
    }

    fun symbolsToBytes(symbols: ByteArray): ByteArray? {
        if (symbols.size != CELL_COUNT) return null
        val out = ByteArray(FRAME_SIZE)
        for (i in 0 until FRAME_SIZE) {
            val j = i * 4
            val a = symbols[j].toInt()
            val b = symbols[j + 1].toInt()
            val c = symbols[j + 2].toInt()
            val d = symbols[j + 3].toInt()
            if (a !in 0..3 || b !in 0..3 || c !in 0..3 || d !in 0..3) return null
            out[i] = ((a shl 6) or (b shl 4) or (c shl 2) or d).toByte()
        }
        return out
    }

    fun parseFrame(bytes: ByteArray): V7TransportFrame {
        if (bytes.size != FRAME_SIZE) throw V7TransportError("frame must be $FRAME_SIZE bytes")
        if (bytes[0] != FRAME_MAGIC[0] || bytes[1] != FRAME_MAGIC[1]) {
            throw V7TransportError("invalid V7 frame magic")
        }
        if ((bytes[2].toInt() and 0xFF) != VERSION) throw V7TransportError("invalid V7 version")
        if ((bytes[3].toInt() and 0xFF) != FLAGS) throw V7TransportError("unsupported V7 flags")

        val expectedCrc = ByteBuffer.wrap(bytes, FRAME_SIZE - 4, 4).int.toLong() and 0xFFFFFFFFL
        val crc = CRC32().apply { update(bytes, 0, FRAME_SIZE - 4) }.value and 0xFFFFFFFFL
        if (crc != expectedCrc) throw V7TransportError("frame CRC32 mismatch")

        val buf = ByteBuffer.wrap(bytes)
        buf.position(4)
        val sessionId = buf.short.toInt() and 0xFFFF
        val frameIdLong = buf.int.toLong() and 0xFFFFFFFFL
        val totalLong = buf.int.toLong() and 0xFFFFFFFFL
        val payloadLen = buf.short.toInt() and 0xFFFF

        if (sessionId == 0) throw V7TransportError("session_id 0 is invalid")
        if (totalLong < 1 || totalLong > Int.MAX_VALUE) throw V7TransportError("unsupported total frame count")
        if (frameIdLong >= totalLong || frameIdLong > Int.MAX_VALUE) throw V7TransportError("invalid frame numbering")
        if (payloadLen > PAYLOAD_SIZE) throw V7TransportError("invalid payload length")

        return V7TransportFrame(
            sessionId = sessionId,
            frameId = frameIdLong.toInt(),
            totalFrames = totalLong.toInt(),
            payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen),
        )
    }

    fun parsePackage(bytes: ByteArray): V7TransferPackage {
        if (bytes.size < PACKAGE_HEADER_SIZE) throw V7TransportError("truncated V7 package")
        for (i in PACKAGE_MAGIC.indices) {
            if (bytes[i] != PACKAGE_MAGIC[i]) throw V7TransportError("invalid V7 package magic")
        }

        val buf = ByteBuffer.wrap(bytes)
        buf.position(4)
        val filenameLen = buf.short.toInt() and 0xFFFF
        val mimeLen = buf.short.toInt() and 0xFFFF
        val fileSizeLong = buf.long
        val expectedCrc = buf.int.toLong() and 0xFFFFFFFFL

        if (filenameLen !in 1..MAX_FILENAME_BYTES) throw V7TransportError("invalid filename length")
        if (mimeLen !in 0..MAX_MIME_BYTES) throw V7TransportError("invalid mime length")
        if (fileSizeLong < 0 || fileSizeLong > Int.MAX_VALUE) throw V7TransportError("file too large for current Android receiver")

        val metaEnd = PACKAGE_HEADER_SIZE + filenameLen + mimeLen
        val expectedTotal = metaEnd.toLong() + fileSizeLong
        if (expectedTotal != bytes.size.toLong()) throw V7TransportError("package length mismatch")

        val filename = try {
            String(bytes, PACKAGE_HEADER_SIZE, filenameLen, Charsets.UTF_8)
        } catch (e: Throwable) {
            throw V7TransportError("invalid filename UTF-8")
        }
        val mime = try {
            String(bytes, PACKAGE_HEADER_SIZE + filenameLen, mimeLen, Charsets.UTF_8)
        } catch (e: Throwable) {
            throw V7TransportError("invalid MIME UTF-8")
        }
        val fileData = bytes.copyOfRange(metaEnd, bytes.size)
        val actualCrc = CRC32().apply { update(fileData) }.value and 0xFFFFFFFFL
        if (actualCrc != expectedCrc) throw V7TransportError("file CRC32 mismatch")

        return V7TransferPackage(
            filename = filename,
            mimeType = if (mime.isBlank()) "application/octet-stream" else mime,
            fileSize = fileData.size,
            fileCrc32 = expectedCrc,
            fileData = fileData,
        )
    }
}

data class V7TransportFrame(
    val sessionId: Int,
    val frameId: Int,
    val totalFrames: Int,
    val payload: ByteArray,
)

data class V7TransferPackage(
    val filename: String,
    val mimeType: String,
    val fileSize: Int,
    val fileCrc32: Long,
    val fileData: ByteArray,
)

class V7TransportError(message: String) : Exception(message)
