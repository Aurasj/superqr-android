package com.superqr.android.vision.v6.transport

class V6TransportError(message: String) : Exception(message)

data class V6TransportFrame(
    val sessionId: Int,
    val frameId: Int,
    val totalFrames: Int,
    val payload: ByteArray,
    val crc16: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as V6TransportFrame

        if (sessionId != other.sessionId) return false
        if (frameId != other.frameId) return false
        if (totalFrames != other.totalFrames) return false
        if (!payload.contentEquals(other.payload)) return false
        if (crc16 != other.crc16) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + frameId
        result = 31 * result + totalFrames
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + crc16
        return result
    }
}

object V6Transport {
    const val MAGIC_BYTE = 0xA5
    const val VERSION_BYTE = 0x06
    const val PAYLOAD_SIZE = 91
    const val FRAME_SIZE = 100

    fun crc16CcittFalse(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                if ((crc and 0x8000) != 0) {
                    crc = ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    crc = (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    fun paletteIndexesToBytes(indexes: IntArray): ByteArray {
        require(indexes.size == 400) { "Must provide exactly 400 palette indexes" }
        val out = ByteArray(100)
        for (i in 0 until 100) {
            val i0 = indexes[i * 4] and 0x03
            val i1 = indexes[i * 4 + 1] and 0x03
            val i2 = indexes[i * 4 + 2] and 0x03
            val i3 = indexes[i * 4 + 3] and 0x03
            val b = (i0 shl 6) or (i1 shl 4) or (i2 shl 2) or i3
            out[i] = b.toByte()
        }
        return out
    }

    fun parseFrame(frameData: ByteArray): V6TransportFrame {
        if (frameData.size != FRAME_SIZE) {
            throw V6TransportError("Frame must be exactly $FRAME_SIZE bytes")
        }

        val expectedCrc = ((frameData[98].toInt() and 0xFF) shl 8) or (frameData[99].toInt() and 0xFF)
        val dataWithoutCrc = frameData.copyOfRange(0, 98)
        val actualCrc = crc16CcittFalse(dataWithoutCrc)

        if (actualCrc != expectedCrc) {
            throw V6TransportError("Frame CRC16 mismatch")
        }

        if ((frameData[0].toInt() and 0xFF) != MAGIC_BYTE) {
            throw V6TransportError("Invalid MAGIC")
        }
        if ((frameData[1].toInt() and 0xFF) != VERSION_BYTE) {
            throw V6TransportError("Invalid VERSION")
        }

        val sessionId = frameData[2].toInt() and 0xFF
        if (sessionId == 0) {
            throw V6TransportError("Session ID 0 is invalid")
        }

        val frameId = ((frameData[3].toInt() and 0xFF) shl 8) or (frameData[4].toInt() and 0xFF)
        val totalFrames = ((frameData[5].toInt() and 0xFF) shl 8) or (frameData[6].toInt() and 0xFF)

        if (totalFrames < 2) {
            throw V6TransportError("Total frames must be >= 2")
        }
        if (frameId >= totalFrames) {
            throw V6TransportError("frame_id must be < total_frames")
        }

        val payload = frameData.copyOfRange(7, 98)

        return V6TransportFrame(
            sessionId = sessionId,
            frameId = frameId,
            totalFrames = totalFrames,
            payload = payload,
            crc16 = actualCrc
        )
    }
}
