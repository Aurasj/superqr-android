package com.superqr.android.vision.v7_capacity_lab

import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.QRCodeDetector
import java.util.zip.CRC32

data class V7Phase1QrResult(
    val decoded: Boolean,
    val valid: Boolean,
    val frameIndex: Long?,
    val bytes: Int,
    val failure: String? = null,
)

/** Binary-safe OpenCV QR control decoder with a reused luma Mat. */
class V7Phase1QrDecoder : AutoCloseable {
    private val gray = Mat()
    private var detector: QRCodeDetector? = null

    fun analyze(luma: ByteArray, width: Int, height: Int, expectedVersion: Int, expectedBytes: Int): V7Phase1QrResult {
        if (detector == null) {
            if (!OpenCVLoader.initLocal()) return V7Phase1QrResult(false, false, null, 0, "OpenCV init failed")
            detector = QRCodeDetector()
        }
        gray.create(height, width, CvType.CV_8UC1)
        gray.put(0, 0, luma)
        val payload = detector!!.detectAndDecodeBytes(gray)
        return validatePayload(payload, expectedVersion, expectedBytes)
    }

    override fun close() {
        gray.release()
    }

    companion object {
        fun validatePayload(payload: ByteArray, expectedVersion: Int, expectedBytes: Int): V7Phase1QrResult {
            if (payload.isEmpty()) return V7Phase1QrResult(false, false, null, 0, "not decoded")
            if (payload.size != expectedBytes) return V7Phase1QrResult(true, false, null, payload.size, "length")
            if (payload.size < 20 || payload[0] != 'S'.code.toByte() || payload[1] != 'Q'.code.toByte() ||
                payload[2] != 'P'.code.toByte() || payload[3] != '1'.code.toByte()
            ) return V7Phase1QrResult(true, false, null, payload.size, "magic")
            if ((payload[4].toInt() and 0xFF) != expectedVersion || (payload[5].toInt() and 0xFF) != 1) {
                return V7Phase1QrResult(true, false, null, payload.size, "profile")
            }
            val frameIndex = readUInt32Le(payload, 6)
            val seed = readUInt32Le(payload, 10)
            val declaredLength = (payload[14].toInt() and 0xFF) or ((payload[15].toInt() and 0xFF) shl 8)
            if (seed != 42L || declaredLength != expectedBytes) {
                return V7Phase1QrResult(true, false, frameIndex, payload.size, "header")
            }
            val crc = CRC32().apply { update(payload, 0, payload.size - 4) }.value
            val expectedCrc = readUInt32Le(payload, payload.size - 4)
            if (crc != expectedCrc) return V7Phase1QrResult(true, false, frameIndex, payload.size, "crc")
            return V7Phase1QrResult(true, true, frameIndex, payload.size)
        }

        private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
