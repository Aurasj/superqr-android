package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.objdetect.QRCodeDetector
import org.opencv.objdetect.QRCodeDetectorAruco
import java.util.zip.CRC32
import kotlin.math.hypot

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
 * what the operator sees. Geometry is only promoted to the reusable decode hot
 * path after a real payload decode succeeds. A geometry-only detection is not
 * trustworthy enough for dense V27/V40 QR controls: in particular the inferred
 * bottom-right corner can move because standard QR has no finder pattern there.
 * While only QR_DETECTED, every QR frame therefore gets a fresh full detection.
 *
 * A separately stabilized quadrangle is returned for overlay/framing guidance so
 * detector jitter does not make the visible border jump around. The stabilizer
 * treats TL/TR/BL as the anchored QR corners. If those three remain effectively
 * static while only BR jumps, BR is held instead of resetting the whole filter.
 * The stabilized overlay never replaces the raw OpenCV geometry used by
 * detectAndDecodeBytes().
 *
 * Dense V27/V40 camera images also get a bounded QRCodeDetectorAruco fallback.
 * OpenCV 5 exposes it as a second QR detector based on ArUco-style finder search;
 * the fallback is only paid after the normal detector misses or finds geometry
 * without decoding a payload.
 */
class V7Phase1QrDecoder : AutoCloseable {
    private var gray: Mat? = null
    private var detector: QRCodeDetector? = null
    private var arucoDetector: QRCodeDetectorAruco? = null
    private var points: Mat? = null
    private var arucoPoints: Mat? = null

    /** True only after these points have produced a non-empty payload. */
    private var trustedPointsValid = false
    private var trustedWithAruco = false
    private var trustedDecodeMisses = 0

    /** UI/guidance geometry; deliberately independent from raw decode geometry. */
    private var stableQuad: List<DoubleArray>? = null
    private var stableQuadMisses = 0

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

    /** Returns the decoded payload plus stable display geometry, when QR geometry exists. */
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

        // Reuse geometry only after that geometry has decoded a real payload.
        if (trustedPointsValid && !detectedPoints.empty()) {
            val payload = if (trustedWithAruco) {
                qrAruco.decodeBytes(target, detectedPoints)
            } else {
                qr.decodeBytes(target, detectedPoints)
            }
            val rawQuad = detectedPoints.toQuad()
            if (payload.isNotEmpty()) {
                trustedDecodeMisses = 0
                return payload to stabilizeQuad(rawQuad)
            }
            trustedDecodeMisses++
            if (trustedDecodeMisses < MAX_TRUSTED_DECODE_MISSES) {
                return ByteArray(0) to stabilizeQuad(rawQuad)
            }
            trustedPointsValid = false
            trustedWithAruco = false
            trustedDecodeMisses = 0
        }

        val payload = qr.detectAndDecodeBytes(target, detectedPoints)
        val standardQuad = detectedPoints.toQuad()
        if (payload.isNotEmpty()) {
            trustedPointsValid = standardQuad != null
            trustedWithAruco = false
            trustedDecodeMisses = 0
            return payload to stabilizeQuad(standardQuad)
        }

        val fallbackPayload = qrAruco.detectAndDecodeBytes(target, fallbackPoints)
        val fallbackQuad = fallbackPoints.toQuad()
        if (fallbackPayload.isNotEmpty()) {
            fallbackPoints.copyTo(detectedPoints)
            trustedPointsValid = fallbackQuad != null
            trustedWithAruco = true
            trustedDecodeMisses = 0
            return fallbackPayload to stabilizeQuad(fallbackQuad)
        }

        val candidateQuad = chooseCandidateQuad(standardQuad, fallbackQuad)
        if (candidateQuad != null) {
            trustedPointsValid = false
            trustedWithAruco = false
            trustedDecodeMisses = 0
            return ByteArray(0) to stabilizeQuad(candidateQuad)
        }

        trustedPointsValid = false
        trustedWithAruco = false
        trustedDecodeMisses = 0
        return ByteArray(0) to holdStableQuadOnMiss()
    }

    private fun chooseCandidateQuad(
        standard: List<DoubleArray>?,
        fallback: List<DoubleArray>?,
    ): List<DoubleArray>? = when {
        standard == null -> fallback
        fallback == null -> standard
        stableQuad == null -> standard
        quadDistance(standard, checkNotNull(stableQuad)) <= quadDistance(fallback, checkNotNull(stableQuad)) -> standard
        else -> fallback
    }

    private fun stabilizeQuad(raw: List<DoubleArray>?): List<DoubleArray>? {
        if (raw == null || raw.size != 4) return holdStableQuadOnMiss()
        stableQuadMisses = 0
        val observed = raw.map { doubleArrayOf(it[0], it[1]) }
        val previous = stableQuad
        if (previous == null || previous.size != 4) {
            stableQuad = observed
            return observed
        }

        // QR finder anchors are TL/TR/BL (indices 0/1/3 in OpenCV ordering).
        // If these three barely move but inferred BR jumps, keep the previous BR.
        val current = suppressBottomRightOnlyJump(previous, observed)
        if (shouldResetSmoothing(previous, current)) {
            stableQuad = current
            return current
        }

        val alpha = QUAD_EMA_ALPHA
        val next = List(4) { index ->
            doubleArrayOf(
                previous[index][0] + (current[index][0] - previous[index][0]) * alpha,
                previous[index][1] + (current[index][1] - previous[index][1]) * alpha,
            )
        }
        stableQuad = next
        return next
    }

    private fun suppressBottomRightOnlyJump(
        previous: List<DoubleArray>,
        current: List<DoubleArray>,
    ): List<DoubleArray> {
        val side = averageSide(current).coerceAtLeast(1.0)
        val anchorStillThreshold = maxOf(MIN_STATIC_ANCHOR_PX, side * STATIC_ANCHOR_SIDE_FRACTION)
        val bottomRightJumpThreshold = maxOf(MIN_BOTTOM_RIGHT_JUMP_PX, side * BOTTOM_RIGHT_JUMP_SIDE_FRACTION)
        val anchorIndices = intArrayOf(0, 1, 3)
        val averageAnchorMotion = anchorIndices.sumOf { index -> pointDistance(previous[index], current[index]) } /
            anchorIndices.size.toDouble()
        val bottomRightMotion = pointDistance(previous[2], current[2])
        if (averageAnchorMotion > anchorStillThreshold || bottomRightMotion <= bottomRightJumpThreshold) {
            return current
        }
        return List(4) { index ->
            if (index == 2) {
                doubleArrayOf(previous[2][0], previous[2][1])
            } else {
                doubleArrayOf(current[index][0], current[index][1])
            }
        }
    }

    private fun holdStableQuadOnMiss(): List<DoubleArray>? {
        val held = stableQuad ?: return null
        stableQuadMisses++
        if (stableQuadMisses > MAX_STABLE_QUAD_HOLD_MISSES) {
            stableQuad = null
            stableQuadMisses = 0
            return null
        }
        return held
    }

    private fun shouldResetSmoothing(previous: List<DoubleArray>, current: List<DoubleArray>): Boolean {
        val side = averageSide(current).coerceAtLeast(1.0)
        val resetDistance = maxOf(MIN_SMOOTHING_RESET_PX, side * SMOOTHING_RESET_SIDE_FRACTION)
        // BR alone is not allowed to reset smoothing. A genuine camera move changes
        // at least two of the three finder-anchored corners TL/TR/BL as well.
        val movedAnchors = intArrayOf(0, 1, 3).count { index ->
            pointDistance(previous[index], current[index]) > resetDistance
        }
        return movedAnchors >= 2
    }

    private fun pointDistance(a: DoubleArray, b: DoubleArray): Double =
        hypot(a[0] - b[0], a[1] - b[1])

    private fun averageSide(quad: List<DoubleArray>): Double {
        if (quad.size != 4) return 0.0
        var sum = 0.0
        for (index in quad.indices) {
            val a = quad[index]
            val b = quad[(index + 1) % quad.size]
            sum += pointDistance(a, b)
        }
        return sum / 4.0
    }

    private fun quadDistance(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.size != 4 || b.size != 4) return Double.POSITIVE_INFINITY
        return a.indices.sumOf { index -> pointDistance(a[index], b[index]) } / 4.0
    }

    private fun Mat.toQuad(): List<DoubleArray>? {
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
        trustedPointsValid = false
        trustedWithAruco = false
        trustedDecodeMisses = 0
        stableQuad = null
        stableQuadMisses = 0
    }

    companion object {
        private const val MAX_TRUSTED_DECODE_MISSES = 2
        private const val MAX_STABLE_QUAD_HOLD_MISSES = 6
        private const val QUAD_EMA_ALPHA = 0.22
        private const val SMOOTHING_RESET_SIDE_FRACTION = 0.18
        private const val MIN_SMOOTHING_RESET_PX = 18.0
        private const val STATIC_ANCHOR_SIDE_FRACTION = 0.02
        private const val MIN_STATIC_ANCHOR_PX = 4.0
        private const val BOTTOM_RIGHT_JUMP_SIDE_FRACTION = 0.03
        private const val MIN_BOTTOM_RIGHT_JUMP_PX = 7.0

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
