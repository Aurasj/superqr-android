package com.superqr.android.vision.v7.transport

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Compatibility-frozen production V40 QR transport parser. */
object V7Transport {
    const val VERSION = 7
    const val HEADER_SIZE = 16
    const val CRC_SIZE = 4
    const val PACKAGE_HEADER_SIZE = 20
    const val MAX_FILENAME_BYTES = 1024
    const val MAX_MIME_BYTES = 255

    private val frameMagic = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte())
    fun parseQrFrame(bytes: ByteArray): V7TransportFrame {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) throw V7TransportError("truncated QR frame")
        if (bytes[0] != frameMagic[0] || bytes[1] != frameMagic[1]) {
            throw V7TransportError("invalid V7 frame magic")
        }
        if ((bytes[2].toInt() and 0xFF) != VERSION) throw V7TransportError("invalid V7 version")

        val expectedCrc = ByteBuffer.wrap(bytes, bytes.size - CRC_SIZE, CRC_SIZE)
            .int.toLong() and 0xFFFFFFFFL
        val actualCrc = CRC32().apply { update(bytes, 0, bytes.size - CRC_SIZE) }
            .value and 0xFFFFFFFFL
        if (actualCrc != expectedCrc) throw V7TransportError("frame CRC32 mismatch")

        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(4)
        val sessionId = buffer.short.toInt() and 0xFFFF
        val frameId = buffer.int
        val totalFrames = buffer.int
        val payloadLength = buffer.short.toInt() and 0xFFFF
        if (sessionId == 0) throw V7TransportError("session_id 0 is invalid")
        if (totalFrames < 1 || frameId !in 0 until totalFrames) {
            throw V7TransportError("invalid frame numbering")
        }
        if (payloadLength > bytes.size - HEADER_SIZE - CRC_SIZE) {
            throw V7TransportError("invalid payload length")
        }
        return V7TransportFrame(
            sessionId,
            frameId,
            totalFrames,
            bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength),
            bytes[3].toInt() and 0xFF,
        )
    }
}

data class V7TransportFrame(
    val sessionId: Int,
    val frameId: Int,
    val totalFrames: Int,
    val payload: ByteArray,
    val profileId: Int,
)

class V7TransportError(message: String) : Exception(message)
