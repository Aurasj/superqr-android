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
    /** QR quadrangle in exact ImageAnalysis luma coordinates. */
    val quad: List<DoubleArray>? = null,
)

/**
 * Binary-safe OpenCV QR control decoder with perspective-correct dense-QR geometry.
 *
 * OpenCV remains the primary payload decoder. Geometry-only V27/V40 detections get
 * a second, independent ZXing geometry pass that requires the bottom-right alignment
 * pattern and builds the same projective transform used by ZXing's QR detector.
 * This avoids trying to infer BR from TL/TR/BL with affine/parallelogram math, which
 * is invalid for a tilted screen under perspective.
 *
 * The alignment-derived outer quadrangle is cached only while the three finder-side
 * corners remain spatially consistent. Meaningful phone motion invalidates it and
 * triggers a fresh projective solve. A frame with no QR geometry clears presentation
 * geometry immediately; no stale border is held in space.
 */
class V7Phase1QrDecoder : AutoCloseable {
    private var gray: Mat? = null
    private var detector: QRCodeDetector? = null
    private var arucoDetector: QRCodeDetectorAruco? = null
    private var points: Mat? = null
    private var arucoPoints: Mat? = null
    private val zxingGeometryDetector = ZxingQrGeometryDetector()

    /** True only after these points have produced a non-empty payload. */
    private var trustedPointsValid = false
    private var trustedWithAruco = false
    private var trustedDecodeMisses = 0

    /** Presentation/guidance geometry; never fed back into OpenCV decoding. */
    private var stableQuad: List<DoubleArray>? = null
    private var projectiveQuad: List<DoubleArray>? = null

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

        if (trustedPointsValid && !detectedPoints.empty()) {
            val payload = if (trustedWithAruco) {
                qrAruco.decodeBytes(target, detectedPoints)
            } else {
                qr.decodeBytes(target, detectedPoints)
            }
            val rawQuad = detectedPoints.toQuad()
            if (payload.isNotEmpty()) {
                trustedDecodeMisses = 0
                return payload to decodedPresentationQuad(rawQuad)
            }
            trustedDecodeMisses++
            if (trustedDecodeMisses < MAX_TRUSTED_DECODE_MISSES) {
                return ByteArray(0) to rawQuad?.let {
                    perspectivePresentationQuad(luma, width, height, it)
                }
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
            return payload to decodedPresentationQuad(standardQuad)
        }

        val fallbackPayload = qrAruco.detectAndDecodeBytes(target, fallbackPoints)
        val fallbackQuad = fallbackPoints.toQuad()
        if (fallbackPayload.isNotEmpty()) {
            fallbackPoints.copyTo(detectedPoints)
            trustedPointsValid = fallbackQuad != null
            trustedWithAruco = true
            trustedDecodeMisses = 0
            return fallbackPayload to decodedPresentationQuad(fallbackQuad)
        }

        val candidateQuad = chooseCandidateQuad(standardQuad, fallbackQuad)
        if (candidateQuad != null) {
            trustedPointsValid = false
            trustedWithAruco = false
            trustedDecodeMisses = 0
            return ByteArray(0) to perspectivePresentationQuad(luma, width, height, candidateQuad)
        }

        trustedPointsValid = false
        trustedWithAruco = false
        trustedDecodeMisses = 0
        clearPresentationGeometry()
        return ByteArray(0) to null
    }

    private fun decodedPresentationQuad(raw: List<DoubleArray>?): List<DoubleArray>? {
        if (raw == null || raw.size != 4) {
            clearPresentationGeometry()
            return null
        }
        val observed = copyQuad(raw)
        stableQuad = observed
        projectiveQuad = null
        return observed
    }

    /**
     * Prefer an alignment-pattern-derived projective quadrangle while OpenCV only
     * has QR geometry. The expensive ZXing solve is refreshed only when finder-side
     * motion means the cached projective mapping no longer matches the current view.
     */
    private fun perspectivePresentationQuad(
        luma: ByteArray,
        width: Int,
        height: Int,
        observedRaw: List<DoubleArray>,
    ): List<DoubleArray> {
        val observed = copyQuad(observedRaw)
        val side = averageAnchoredSpan(observed).coerceAtLeast(1.0)
        val motionThreshold = maxOf(MIN_PROJECTIVE_REFRESH_PX, side * PROJECTIVE_REFRESH_SIDE_FRACTION)
        val cached = projectiveQuad
        val needsRefresh = cached == null || anchorDistance(cached, observed) > motionThreshold

        if (needsRefresh) {
            val solved = zxingGeometryDetector.detectOuterQuad(luma, width, height)
            if (solved != null && projectiveSolutionMatchesObserved(solved, observed, side)) {
                projectiveQuad = copyQuad(solved)
                stableQuad = copyQuad(solved)
                return copyQuad(solved)
            }

            // Never hold an old perspective solve after meaningful camera motion.
            if (cached != null) projectiveQuad = null
        }

        projectiveQuad?.let {
            stableQuad = copyQuad(it)
            return copyQuad(it)
        }

        // ZXing may fail on an individual frame. In that case surface the current
        // real OpenCV quadrangle rather than inventing or holding a synthetic BR.
        stableQuad = observed
        return observed
    }

    private fun projectiveSolutionMatchesObserved(
        solved: List<DoubleArray>,
        observed: List<DoubleArray>,
        side: Double,
    ): Boolean {
        if (solved.size != 4 || observed.size != 4) return false
        val maxAnchorDifference = maxOf(MIN_PROJECTIVE_MATCH_PX, side * PROJECTIVE_MATCH_SIDE_FRACTION)
        return anchorDistance(solved, observed) <= maxAnchorDifference
    }

    private fun chooseCandidateQuad(
        standard: List<DoubleArray>?,
        fallback: List<DoubleArray>?,
    ): List<DoubleArray>? = when {
        standard == null -> fallback
        fallback == null -> standard
        stableQuad == null -> standard
        anchorDistance(standard, checkNotNull(stableQuad)) <=
            anchorDistance(fallback, checkNotNull(stableQuad)) -> standard
        else -> fallback
    }

    private fun copyQuad(quad: List<DoubleArray>): List<DoubleArray> =
        quad.map { doubleArrayOf(it[0], it[1]) }

    private fun clearPresentationGeometry() {
        stableQuad = null
        projectiveQuad = null
    }

    private fun pointDistance(a: DoubleArray, b: DoubleArray): Double =
        hypot(a[0] - b[0], a[1] - b[1])

    private fun anchorDistance(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.size != 4 || b.size != 4) return Double.POSITIVE_INFINITY
        val anchors = intArrayOf(0, 1, 3)
        return anchors.sumOf { index -> pointDistance(a[index], b[index]) } / anchors.size.toDouble()
    }

    private fun averageAnchoredSpan(quad: List<DoubleArray>): Double {
        if (quad.size != 4) return 0.0
        val top = pointDistance(quad[0], quad[1])
        val left = pointDistance(quad[0], quad[3])
        return (top + left) * 0.5
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
        clearPresentationGeometry()
    }

    companion object {
        private const val MAX_TRUSTED_DECODE_MISSES = 1
        private const val PROJECTIVE_REFRESH_SIDE_FRACTION = 0.015
        private const val MIN_PROJECTIVE_REFRESH_PX = 3.0
        private const val PROJECTIVE_MATCH_SIDE_FRACTION = 0.15
        private const val MIN_PROJECTIVE_MATCH_PX = 16.0

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
