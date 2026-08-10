package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.QRCodeDetector
import java.util.zip.CRC32

data class V7Phase1QrResult(
    val decoded: Boolean,
    val valid: Boolean,
    val frameIndex: Long?,
    val bytes: Int,
    val envelope: V7LabRunEnvelope? = null,
    val failure: String? = null,
)

/**
 * Binary-safe OpenCV QR control decoder with reused luma and QR geometry.
 *
 * Full-frame detection is required while searching so the receiver sees exactly
 * what the operator sees. Once OpenCV has found a QR quadrangle, however, the
 * phone/display geometry is effectively static for a physical lab run. Reusing
 * those points through decodeBytes() removes repeated full-image finder scans
 * from the hot path. Bounded misses fall back to full detection so V27 -> V40,
 * phone motion, and reacquisition remain safe.
 */
class V7Phase1QrDecoder : AutoCloseable {
    private var gray: Mat? = null
    private var detector: QRCodeDetector? = null
    private var points: Mat? = null
    private var trackedPointsValid = false
    private var trackedDecodeMisses = 0

    fun analyze(luma: ByteArray, width: Int, height: Int, expectedVersion: Int, expectedBytes: Int): V7Phase1QrResult {
        val payload = decode(luma, width, height)
        return validatePayload(payload, expectedVersion, expectedBytes)
    }

    fun analyzeAuto(luma: ByteArray, width: Int, height: Int, expectedBytesByVersion: Map<Int, Int>): V7Phase1QrResult {
        val payload = decode(luma, width, height)
        if (payload.isEmpty()) return V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED")
        if (payload.size < 5) return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_HEADER")
        val version = payload[4].toInt() and 0xFF
        val expectedBytes = expectedBytesByVersion[version]
            ?: return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_UNSUPPORTED_VERSION")
        return validatePayload(payload, version, expectedBytes)
    }

    private fun decode(luma: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && luma.size >= width * height)
        ensureInitialized()
        val target = checkNotNull(gray)
        val qr = checkNotNull(detector)
        val detectedPoints = checkNotNull(points)
        target.create(height, width, CvType.CV_8UC1)
        target.put(0, 0, luma)

        if (trackedPointsValid && !detectedPoints.empty()) {
            val payload = qr.decodeBytes(target, detectedPoints)
            if (payload.isNotEmpty()) {
                trackedDecodeMisses = 0
                return payload
            }
            trackedDecodeMisses++
            if (trackedDecodeMisses < MAX_TRACKED_DECODE_MISSES) return ByteArray(0)
            trackedPointsValid = false
            trackedDecodeMisses = 0
        }

        // Search the exact complete normalized ImageAnalysis frame. Supplying an
        // output points Mat lets the next frames bypass detection once geometry
        // is known. Even if this frame is a rolling transition and payload decode
        // fails, useful detected points may still seed the next frame's fast path.
        val payload = qr.detectAndDecodeBytes(target, detectedPoints)
        trackedPointsValid = !detectedPoints.empty()
        trackedDecodeMisses = 0
        return payload
    }

    private fun ensureInitialized() {
        if (detector != null) return
        OpenCvRuntime.ensureLoaded()
        gray = Mat()
        detector = QRCodeDetector()
        points = Mat()
    }

    override fun close() {
        gray?.release()
        points?.release()
        gray = null
        points = null
        detector = null
        trackedPointsValid = false
        trackedDecodeMisses = 0
    }

    companion object {
        private const val MAX_TRACKED_DECODE_MISSES = 2

        fun validatePayload(payload: ByteArray, expectedVersion: Int, expectedBytes: Int): V7Phase1QrResult {
            if (payload.isEmpty()) return V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED")
            if (payload.size != expectedBytes) return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_LENGTH")
            if (payload.size < 30 || payload[0] != 'S'.code.toByte() || payload[1] != 'Q'.code.toByte() ||
                payload[2] != 'P'.code.toByte() || payload[3] != '1'.code.toByte()
            ) return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_MAGIC")
            if ((payload[4].toInt() and 0xFF) != expectedVersion || (payload[5].toInt() and 0xFF) != 1) {
                return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_PROFILE")
            }
            val frameIndex = readUInt32Le(payload, 6)
            val seed = readUInt32Le(payload, 10)
            val declaredLength = (payload[14].toInt() and 0xFF) or ((payload[15].toInt() and 0xFF) shl 8)
            if (seed != 42L || declaredLength != expectedBytes) {
                return V7Phase1QrResult(true, false, frameIndex, payload.size, failure = "QR_HEADER")
            }
            val envelope = V7LabRunEnvelope.decode(payload, 16)
                ?: return V7Phase1QrResult(true, false, frameIndex, payload.size, failure = "QR_RUN_SYNC")
            if (envelope.frameIndex.toLong() != frameIndex) {
                return V7Phase1QrResult(true, false, frameIndex, payload.size, envelope, "QR_INDEX_MISMATCH")
            }
            val crc = CRC32().apply { update(payload, 0, payload.size - 4) }.value
            val expectedCrc = readUInt32Le(payload, payload.size - 4)
            if (crc != expectedCrc) return V7Phase1QrResult(true, false, frameIndex, payload.size, envelope, "QR_CRC")
            return V7Phase1QrResult(true, true, frameIndex, payload.size, envelope)
        }

        private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
