package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.objdetect.QRCodeDetector
import org.opencv.objdetect.QRCodeDetectorAruco
import java.util.zip.CRC32
import kotlin.math.abs
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
 * Binary-safe OpenCV QR control decoder with a bounded ZXing fallback for dense QR.
 *
 * OpenCV remains the first payload path. When V27/V40 are only geometrically
 * detected, one ZXing detector pass can both resolve alignment-based projective
 * geometry and decode the already sampled QR BitMatrix. ZXing BYTE-mode segments
 * are returned as the binary payload fallback.
 *
 * A successful projective solve is anchored to the OpenCV TL/TR/BL observation from
 * that exact frame. Small hand motion transports the already perspective-correct
 * quadrangle with those three finder anchors, rather than rerunning TRY_HARDER on
 * every frame. Meaningful motion triggers a fresh projective solve. Decode retries
 * are also throttled, so dense QR cannot dominate the camera hot path.
 */
class V7Phase1QrDecoder : AutoCloseable {
    private var gray: Mat? = null
    private var detector: QRCodeDetector? = null
    private var arucoDetector: QRCodeDetectorAruco? = null
    private var points: Mat? = null
    private var arucoPoints: Mat? = null
    private val zxingGeometryDetector = ZxingQrGeometryDetector()

    /** True only after these OpenCV points have produced a non-empty payload. */
    private var trustedPointsValid = false
    private var trustedWithAruco = false
    private var trustedDecodeMisses = 0

    /** Presentation/guidance geometry; never fed back into OpenCV decoding. */
    private var stableQuad: List<DoubleArray>? = null
    private var projectiveQuad: List<DoubleArray>? = null
    private var projectiveReferenceAnchors: List<DoubleArray>? = null
    private var zxingRetryCountdown = 0

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
                return ByteArray(0) to rawQuad
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
            val fallback = denseQrFallback(luma, width, height, candidateQuad)
            return fallback.payload to fallback.quad
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
        projectiveReferenceAnchors = null
        zxingRetryCountdown = 0
        return observed
    }

    private data class DenseFallbackResult(
        val payload: ByteArray,
        val quad: List<DoubleArray>,
    )

    /**
     * Throttled dense-QR fallback. A projective solve is refreshed only after
     * meaningful finder-anchor motion; otherwise the cached projective quadrangle
     * is transported with the live TL/TR/BL anchors. ZXing payload decoding is
     * retried periodically even while geometry remains cached.
     */
    private fun denseQrFallback(
        luma: ByteArray,
        width: Int,
        height: Int,
        observedRaw: List<DoubleArray>,
    ): DenseFallbackResult {
        val observed = copyQuad(observedRaw)
        val side = averageAnchoredSpan(observed).coerceAtLeast(1.0)
        val refreshThreshold = maxOf(MIN_PROJECTIVE_REFRESH_PX, side * PROJECTIVE_REFRESH_SIDE_FRACTION)
        val reference = projectiveReferenceAnchors
        val geometryNeedsRefresh = projectiveQuad == null || reference == null ||
            anchorDistance(reference, observed) > refreshThreshold
        val shouldRunZxing = geometryNeedsRefresh || zxingRetryCountdown <= 0

        if (shouldRunZxing) {
            val analysis = zxingGeometryDetector.analyze(luma, width, height)
            zxingRetryCountdown = ZXING_RETRY_INTERVAL_FRAMES

            val solved = analysis?.outerQuad
            val acceptedSolve = solved != null && projectiveSolutionMatchesObserved(solved, observed, side)
            if (acceptedSolve) {
                projectiveQuad = copyQuad(checkNotNull(solved))
                projectiveReferenceAnchors = copyQuad(observed)
            } else if (geometryNeedsRefresh) {
                // Never hold a stale perspective solve after meaningful motion.
                projectiveQuad = null
                projectiveReferenceAnchors = null
            }

            val presentation = currentProjectivePresentation(observed) ?: observed
            stableQuad = copyQuad(presentation)
            val zxingPayload = analysis?.payload ?: ByteArray(0)
            if (zxingPayload.isNotEmpty()) {
                return DenseFallbackResult(zxingPayload, presentation)
            }
            return DenseFallbackResult(ByteArray(0), presentation)
        }

        zxingRetryCountdown--
        val presentation = currentProjectivePresentation(observed) ?: observed
        stableQuad = copyQuad(presentation)
        return DenseFallbackResult(ByteArray(0), presentation)
    }

    /**
     * Carry an already perspective-correct quad through small inter-frame hand
     * motion using the current finder anchors. This does not infer BR from three
     * corners; BR came from an alignment-based projective solve. The affine carry
     * is only an inter-frame approximation until the next projective refresh.
     */
    private fun currentProjectivePresentation(observed: List<DoubleArray>): List<DoubleArray>? {
        val sourceQuad = projectiveQuad ?: return null
        val reference = projectiveReferenceAnchors ?: return null
        return transportQuad(reference, observed, sourceQuad)
    }

    private fun transportQuad(
        reference: List<DoubleArray>,
        current: List<DoubleArray>,
        sourceQuad: List<DoubleArray>,
    ): List<DoubleArray>? {
        if (reference.size != 4 || current.size != 4 || sourceQuad.size != 4) return null
        val a = reference[0]
        val b = reference[1]
        val c = reference[3]
        val bx = b[0] - a[0]
        val by = b[1] - a[1]
        val cx = c[0] - a[0]
        val cy = c[1] - a[1]
        val det = bx * cy - by * cx
        if (!det.isFinite() || abs(det) < 1e-6) return null

        val na = current[0]
        val nb = current[1]
        val nc = current[3]
        val nbx = nb[0] - na[0]
        val nby = nb[1] - na[1]
        val ncx = nc[0] - na[0]
        val ncy = nc[1] - na[1]

        val transported = sourceQuad.map { point ->
            val px = point[0] - a[0]
            val py = point[1] - a[1]
            val u = (px * cy - py * cx) / det
            val v = (bx * py - by * px) / det
            val x = na[0] + u * nbx + v * ncx
            val y = na[1] + u * nby + v * ncy
            doubleArrayOf(x, y)
        }
        if (transported.any { point -> point.any { coordinate -> !coordinate.isFinite() } }) return null
        return transported
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
        projectiveReferenceAnchors = null
        zxingRetryCountdown = 0
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
        private const val ZXING_RETRY_INTERVAL_FRAMES = 8
        private const val PROJECTIVE_REFRESH_SIDE_FRACTION = 0.04
        private const val MIN_PROJECTIVE_REFRESH_PX = 6.0
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
