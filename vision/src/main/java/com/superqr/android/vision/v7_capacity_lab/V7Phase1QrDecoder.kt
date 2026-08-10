package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.objdetect.QRCodeDetector
import org.opencv.objdetect.QRCodeDetectorAruco
import java.util.zip.CRC32

data class V7Phase1QrResult(
    val decoded: Boolean,
    val valid: Boolean,
    val frameIndex: Long?,
    val bytes: Int,
    val envelope: V7LabRunEnvelope? = null,
    val failure: String? = null,
    /** Last detected QR quadrangle in exact ImageAnalysis luma coordinates, when OpenCV found one. */
    val quad: List<DoubleArray>? = null,
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
 *
 * Dense V27/V40 camera images also get a bounded QRCodeDetectorAruco fallback.
 * OpenCV 5 exposes it as a second QR detector based on ArUco-style finder search;
 * the fallback is only paid after the normal detector misses.
 */
class V7Phase1QrDecoder : AutoCloseable {
    private var gray: Mat? = null
    private var detector: QRCodeDetector? = null
    private var arucoDetector: QRCodeDetectorAruco? = null
    private var points: Mat? = null
    private var arucoPoints: Mat? = null
    private var trackedPointsValid = false
    private var trackedWithAruco = false
    private var trackedDecodeMisses = 0

    fun analyze(luma: ByteArray, width: Int, height: Int, expectedVersion: Int, expectedBytes: Int): V7Phase1QrResult {
        val (payload, quad) = decode(luma, width, height)
        return validatePayload(payload, expectedVersion, expectedBytes, quad)
    }

    fun analyzeAuto(luma: ByteArray, width: Int, height: Int, expectedBytesByVersion: Map<Int, Int>): V7Phase1QrResult {
        val (payload, quad) = decode(luma, width, height)
        if (payload.isEmpty()) return V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED", quad = quad)
        if (payload.size < 5) return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_HEADER", quad = quad)
        val version = payload[4].toInt() and 0xFF
        val expectedBytes = expectedBytesByVersion[version]
            ?: return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_UNSUPPORTED_VERSION", quad = quad)
        return validatePayload(payload, version, expectedBytes, quad)
    }

    /** Returns the decoded payload plus the last detected quadrangle, when OpenCV found one. */
    private fun decode(luma: ByteArray, width: Int, height: Int): Pair<ByteArray, List<DoubleArray>?> {
        require(width > 0 && height > 0 && luma.size >= width * height)
        ensureInitialized()
        val target = checkNotNull(gray)
        val qr = checkNotNull(detector)
        val qrAruco = checkNotNull(arucoDetector)
        val detectedPoints = checkNotNull(points)
        val fallbackPoints = checkNotNull(arucoPoints)
        target.create(height, width, CvType.CV_8UC1)
        target.put(0, 0, luma)

        if (trackedPointsValid && !detectedPoints.empty()) {
            val payload = if (trackedWithAruco) {
                qrAruco.decodeBytes(target, detectedPoints)
            } else {
                qr.decodeBytes(target, detectedPoints)
            }
            if (payload.isNotEmpty()) {
                trackedDecodeMisses = 0
                return payload to detectedPoints.toQuad()
            }
            trackedDecodeMisses++
            if (trackedDecodeMisses < MAX_TRACKED_DECODE_MISSES) {
                return ByteArray(0) to detectedPoints.toQuad()
            }
            trackedPointsValid = false
            trackedWithAruco = false
            trackedDecodeMisses = 0
        }

        // Fast/default path first.
        val payload = qr.detectAndDecodeBytes(target, detectedPoints)
        if (payload.isNotEmpty() || !detectedPoints.empty()) {
            trackedPointsValid = !detectedPoints.empty()
            trackedWithAruco = false
            trackedDecodeMisses = 0
            if (payload.isNotEmpty()) return payload to detectedPoints.toQuad()

            // The normal detector found geometry but failed to decode. Dense camera
            // captures can still benefit from the alternate finder implementation.
            val fallbackPayload = qrAruco.detectAndDecodeBytes(target, fallbackPoints)
            if (fallbackPayload.isNotEmpty() || !fallbackPoints.empty()) {
                fallbackPoints.copyTo(detectedPoints)
                trackedPointsValid = true
                trackedWithAruco = true
                return fallbackPayload to detectedPoints.toQuad()
            }
            return ByteArray(0) to detectedPoints.toQuad()
        }

        // No standard QR geometry: try OpenCV 5's ArUco-based QR detector once.
        val fallbackPayload = qrAruco.detectAndDecodeBytes(target, fallbackPoints)
        if (fallbackPayload.isNotEmpty() || !fallbackPoints.empty()) {
            fallbackPoints.copyTo(detectedPoints)
            trackedPointsValid = true
            trackedWithAruco = true
            trackedDecodeMisses = 0
            return fallbackPayload to detectedPoints.toQuad()
        }

        trackedPointsValid = false
        trackedWithAruco = false
        trackedDecodeMisses = 0
        return ByteArray(0) to null
    }

    private fun Mat.toQuad(): List<DoubleArray>? {
        // OpenCV commonly returns one QR as a 1x4 CV_32FC2 Mat, not four rows.
        // total() counts the four 2-channel point elements regardless of whether
        // the Java binding exposes them as 1x4 or 4x1. The previous rows() < 4
        // guard therefore discarded genuine QR geometry on Android.
        if (empty() || total() < 4L) return null
        val mat2f = MatOfPoint2f()
        return try {
            convertTo(mat2f, CvType.CV_32FC2)
            val pts = mat2f.toArray()
            if (pts.size < 4) null else List(4) { doubleArrayOf(pts[it].x, pts[it].y) }
        } catch (_: Throwable) {
            null
        } finally {
            mat2f.release()
        }
    }

    private fun ensureInitialized() {
        if (detector != null) return
        OpenCvRuntime.ensureLoaded()
        gray = Mat()
        detector = QRCodeDetector().apply {
            setUseAlignmentMarkers(true)
        }
        arucoDetector = QRCodeDetectorAruco()
        points = Mat()
        arucoPoints = Mat()
    }

    override fun close() {
        gray?.release()
        points?.release()
        arucoPoints?.release()
        gray = null
        points = null
        arucoPoints = null
        detector = null
        arucoDetector = null
        trackedPointsValid = false
        trackedWithAruco = false
        trackedDecodeMisses = 0
    }

    companion object {
        private const val MAX_TRACKED_DECODE_MISSES = 2

        fun validatePayload(
            payload: ByteArray,
            expectedVersion: Int,
            expectedBytes: Int,
            quad: List<DoubleArray>? = null,
        ): V7Phase1QrResult {
            if (payload.isEmpty()) return V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED", quad = quad)
            if (payload.size != expectedBytes) return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_LENGTH", quad = quad)
            if (payload.size < 30 || payload[0] != 'S'.code.toByte() || payload[1] != 'Q'.code.toByte() ||
                payload[2] != 'P'.code.toByte() || payload[3] != '1'.code.toByte()
            ) return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_MAGIC", quad = quad)
            if ((payload[4].toInt() and 0xFF) != expectedVersion || (payload[5].toInt() and 0xFF) != 1) {
                return V7Phase1QrResult(true, false, null, payload.size, failure = "QR_PROFILE", quad = quad)
            }
            val frameIndex = readUInt32Le(payload, 6)
            val seed = readUInt32Le(payload, 10)
            val declaredLength = (payload[14].toInt() and 0xFF) or ((payload[15].toInt() and 0xFF) shl 8)
            if (seed != 42L || declaredLength != expectedBytes) {
                return V7Phase1QrResult(true, false, frameIndex, payload.size, failure = "QR_HEADER", quad = quad)
            }
            val envelope = V7LabRunEnvelope.decode(payload, 16)
                ?: return V7Phase1QrResult(true, false, frameIndex, payload.size, failure = "QR_RUN_SYNC", quad = quad)
            if (envelope.frameIndex.toLong() != frameIndex) {
                return V7Phase1QrResult(true, false, frameIndex, payload.size, envelope, "QR_INDEX_MISMATCH", quad)
            }
            val crc = CRC32().apply { update(payload, 0, payload.size - 4) }.value
            val expectedCrc = readUInt32Le(payload, payload.size - 4)
            if (crc != expectedCrc) return V7Phase1QrResult(true, false, frameIndex, payload.size, envelope, "QR_CRC", quad)
            return V7Phase1QrResult(true, true, frameIndex, payload.size, envelope, quad = quad)
        }

        private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
