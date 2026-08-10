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
    /** Phase 0 decoder-path telemetry. It never participates in decode decisions. */
    val diagnostics: Map<String, Any?> = emptyMap(),
)

/**
 * Binary-safe Phase 1 QR control decoder.
 *
 * A frame-scoped native decoder may supply bytes directly from the CameraX Y plane.
 * When that succeeds, OpenCV performs geometry detection only; payload validation is
 * still the same SuperQR length/header/run-envelope/CRC contract below. This keeps
 * the proven OpenCV presentation geometry without paying its dense QR decode cost.
 *
 * If the external decoder does not produce bytes, the existing OpenCV standard ->
 * conditional ArUco -> Java ZXing known-dimension path remains a bounded fallback.
 */
class V7Phase1QrDecoder : AutoCloseable {
    private var gray: Mat? = null
    private var detector: QRCodeDetector? = null
    private var arucoDetector: QRCodeDetectorAruco? = null
    private var points: Mat? = null
    private var arucoPoints: Mat? = null
    private val zxingGeometryDetector = ZxingQrGeometryDetector()
    private val lastDiagnostics = LinkedHashMap<String, Any?>()

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
        val expectedDimensions = intArrayOf(qrDimension(expectedVersion))
        val (payload, quad) = decode(luma, width, height, expectedDimensions)
        return attachDiagnostics(validatePayload(payload, expectedVersion, expectedBytes, quad))
    }

    fun analyzeAuto(
        luma: ByteArray,
        width: Int,
        height: Int,
        expectedBytesByVersion: Map<Int, Int>,
        external: ExternalQrDecodeResult? = null,
    ): V7Phase1QrResult {
        val expectedDimensions = expectedBytesByVersion.keys
            .map(::qrDimension)
            .distinct()
            .sorted()
            .toIntArray()

        val (payload, quad) = if (external != null && external.payload.isNotEmpty()) {
            decodeExternalPayload(luma, width, height, expectedDimensions, external)
        } else {
            val decoded = decode(luma, width, height, expectedDimensions)
            recordExternalDiagnostics(external)
            decoded
        }

        if (payload.isEmpty()) {
            return attachDiagnostics(
                V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED", quad = quad)
            )
        }
        if (payload.size < 5) {
            return attachDiagnostics(
                V7Phase1QrResult(true, false, null, payload.size, failure = "QR_HEADER", quad = quad)
            )
        }
        val version = payload[4].toInt() and 0xFF
        val expectedBytes = expectedBytesByVersion[version]
            ?: return attachDiagnostics(
                V7Phase1QrResult(
                    true,
                    false,
                    null,
                    payload.size,
                    failure = "QR_UNSUPPORTED_VERSION",
                    quad = quad,
                )
            )
        return attachDiagnostics(validatePayload(payload, version, expectedBytes, quad))
    }

    private fun attachDiagnostics(result: V7Phase1QrResult): V7Phase1QrResult =
        result.copy(diagnostics = LinkedHashMap(lastDiagnostics))

    private fun decodeExternalPayload(
        luma: ByteArray,
        width: Int,
        height: Int,
        expectedDimensions: IntArray,
        external: ExternalQrDecodeResult,
    ): Pair<ByteArray, List<DoubleArray>?> {
        require(width > 0 && height > 0 && luma.size >= width * height)
        lastDiagnostics.clear()
        lastDiagnostics["decode_source"] = external.source
        lastDiagnostics["known_qr_dimensions"] = expectedDimensions.copyOf()
        recordExternalDiagnostics(external)
        ensureInitialized()

        val target = checkNotNull(gray)
        val qr = checkNotNull(detector)
        val detectedPoints = checkNotNull(points)
        target.create(height, width, CvType.CV_8UC1)
        target.put(0, 0, luma)

        val geometryStartNs = System.nanoTime()
        val detected = try {
            qr.detect(target, detectedPoints)
        } catch (_: Throwable) {
            false
        }
        val rawQuad = if (detected) detectedPoints.toQuad() else null
        lastDiagnostics["opencv_geometry_only_ms"] = elapsedMs(geometryStartNs)
        lastDiagnostics["opencv_geometry_only_found"] = rawQuad != null

        trustedPointsValid = false
        trustedWithAruco = false
        trustedDecodeMisses = 0
        val presentation = if (rawQuad != null) decodedPresentationQuad(rawQuad) else {
            clearPresentationGeometry()
            null
        }
        return external.payload to presentation
    }

    private fun recordExternalDiagnostics(external: ExternalQrDecodeResult?) {
        lastDiagnostics["external_qr_attempted"] = external != null
        if (external == null) return
        lastDiagnostics["external_qr_source"] = external.source
        lastDiagnostics["external_qr_ms"] = external.elapsedMs
        lastDiagnostics["external_qr_result_count"] = external.resultCount
        lastDiagnostics["external_qr_payload_bytes"] = external.payload.size
        external.errorType?.let { lastDiagnostics["external_qr_error"] = it }
        external.errorMessage?.takeIf { it.isNotBlank() }?.let { lastDiagnostics["external_qr_error_message"] = it }
    }

    private fun decode(
        luma: ByteArray,
        width: Int,
        height: Int,
        expectedDimensions: IntArray,
    ): Pair<ByteArray, List<DoubleArray>?> {
        require(width > 0 && height > 0 && luma.size >= width * height)
        lastDiagnostics.clear()
        lastDiagnostics["decode_source"] = "NONE"
        lastDiagnostics["known_qr_dimensions"] = expectedDimensions.copyOf()
        ensureInitialized()
        val target = checkNotNull(gray)
        val qr = checkNotNull(detector)
        val qrAruco = checkNotNull(arucoDetector)
        val detectedPoints = checkNotNull(points)
        val fallbackPoints = checkNotNull(arucoPoints)
        target.create(height, width, CvType.CV_8UC1)
        target.put(0, 0, luma)

        if (trustedPointsValid && !detectedPoints.empty()) {
            val trustedStartNs = System.nanoTime()
            val payload = if (trustedWithAruco) {
                qrAruco.decodeBytes(target, detectedPoints)
            } else {
                qr.decodeBytes(target, detectedPoints)
            }
            val rawQuad = detectedPoints.toQuad()
            lastDiagnostics["opencv_trusted_attempted"] = true
            lastDiagnostics["opencv_trusted_ms"] = elapsedMs(trustedStartNs)
            lastDiagnostics["opencv_trusted_geometry"] = rawQuad != null
            lastDiagnostics["opencv_trusted_payload_bytes"] = payload.size
            if (payload.isNotEmpty()) {
                lastDiagnostics["decode_source"] = if (trustedWithAruco) "OPENCV_ARUCO_TRUSTED" else "OPENCV_STANDARD_TRUSTED"
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
        } else {
            lastDiagnostics["opencv_trusted_attempted"] = false
        }

        val standardStartNs = System.nanoTime()
        val payload = qr.detectAndDecodeBytes(target, detectedPoints)
        val standardMs = elapsedMs(standardStartNs)
        val standardQuad = detectedPoints.toQuad()
        lastDiagnostics["opencv_standard_ms"] = standardMs
        lastDiagnostics["opencv_standard_geometry"] = standardQuad != null
        lastDiagnostics["opencv_standard_payload_bytes"] = payload.size
        if (payload.isNotEmpty()) {
            lastDiagnostics["decode_source"] = "OPENCV_STANDARD"
            trustedPointsValid = standardQuad != null
            trustedWithAruco = false
            trustedDecodeMisses = 0
            return payload to decodedPresentationQuad(standardQuad)
        }

        var fallbackQuad: List<DoubleArray>? = null
        if (standardQuad == null) {
            lastDiagnostics["opencv_aruco_attempted"] = true
            val arucoStartNs = System.nanoTime()
            val fallbackPayload = qrAruco.detectAndDecodeBytes(target, fallbackPoints)
            val arucoMs = elapsedMs(arucoStartNs)
            fallbackQuad = fallbackPoints.toQuad()
            lastDiagnostics["opencv_aruco_ms"] = arucoMs
            lastDiagnostics["opencv_aruco_geometry"] = fallbackQuad != null
            lastDiagnostics["opencv_aruco_payload_bytes"] = fallbackPayload.size
            if (fallbackPayload.isNotEmpty()) {
                lastDiagnostics["decode_source"] = "OPENCV_ARUCO"
                fallbackPoints.copyTo(detectedPoints)
                trustedPointsValid = fallbackQuad != null
                trustedWithAruco = true
                trustedDecodeMisses = 0
                return fallbackPayload to decodedPresentationQuad(fallbackQuad)
            }
        } else {
            lastDiagnostics["opencv_aruco_attempted"] = false
        }

        val candidateQuad = standardQuad ?: fallbackQuad
        if (candidateQuad != null) {
            trustedPointsValid = false
            trustedWithAruco = false
            trustedDecodeMisses = 0
            val fallback = denseQrFallback(luma, width, height, candidateQuad, expectedDimensions)
            if (fallback.payload.isNotEmpty()) lastDiagnostics["decode_source"] = "ZXING"
            return fallback.payload to fallback.quad
        }

        lastDiagnostics["zxing_attempted"] = false
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
        expectedDimensions: IntArray,
    ): DenseFallbackResult {
        val observed = copyQuad(observedRaw)
        val side = averageAnchoredSpan(observed).coerceAtLeast(1.0)
        val refreshThreshold = maxOf(MIN_PROJECTIVE_REFRESH_PX, side * PROJECTIVE_REFRESH_SIDE_FRACTION)
        val reference = projectiveReferenceAnchors
        val geometryNeedsRefresh = projectiveQuad == null || reference == null ||
            anchorDistance(reference, observed) > refreshThreshold
        val shouldRunZxing = geometryNeedsRefresh || zxingRetryCountdown <= 0

        if (shouldRunZxing) {
            lastDiagnostics["zxing_attempted"] = true
            val analysis = zxingGeometryDetector.analyze(luma, width, height, expectedDimensions)
            recordZxingDiagnostics(analysis)
            zxingRetryCountdown = ZXING_RETRY_INTERVAL_FRAMES

            val solved = analysis?.outerQuad
            val acceptedSolve = solved != null && projectiveSolutionMatchesObserved(solved, observed, side)
            lastDiagnostics["zxing_projective_accepted"] = acceptedSolve
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

        lastDiagnostics["zxing_attempted"] = false
        lastDiagnostics["zxing_retry_countdown"] = zxingRetryCountdown
        zxingRetryCountdown--
        val presentation = currentProjectivePresentation(observed) ?: observed
        stableQuad = copyQuad(presentation)
        return DenseFallbackResult(ByteArray(0), presentation)
    }

    private fun recordZxingDiagnostics(analysis: ZxingQrAnalysis?) {
        if (analysis == null) {
            lastDiagnostics["zxing_stage"] = "NO_RESULT"
            return
        }
        lastDiagnostics["zxing_stage"] = analysis.stage
        lastDiagnostics["zxing_payload_bytes"] = analysis.payload.size
        lastDiagnostics["zxing_projective_geometry"] = analysis.outerQuad != null
        analysis.exceptionType?.let { lastDiagnostics["zxing_exception"] = it }
        analysis.exceptionMessage?.takeIf { it.isNotBlank() }?.let { lastDiagnostics["zxing_exception_message"] = it }
        analysis.dimension?.let { lastDiagnostics["zxing_dimension"] = it }
        analysis.sampledDimension?.let { lastDiagnostics["zxing_sampled_dimension"] = it }
        lastDiagnostics["zxing_forced_dimension"] = analysis.forcedDimension
        lastDiagnostics["zxing_point_count"] = analysis.pointCount
        analysis.detectMs?.let { lastDiagnostics["zxing_detect_ms"] = it }
        analysis.sampleMs?.let { lastDiagnostics["zxing_sample_ms"] = it }
        analysis.decodeMs?.let { lastDiagnostics["zxing_decode_ms"] = it }
        lastDiagnostics["zxing_total_ms"] = analysis.totalMs
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

    private fun elapsedMs(startNs: Long): Double =
        (System.nanoTime() - startNs) / 1_000_000.0

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
        lastDiagnostics.clear()
        clearPresentationGeometry()
    }

    companion object {
        private const val MAX_TRUSTED_DECODE_MISSES = 1
        private const val ZXING_RETRY_INTERVAL_FRAMES = 8
        private const val PROJECTIVE_REFRESH_SIDE_FRACTION = 0.04
        private const val MIN_PROJECTIVE_REFRESH_PX = 6.0
        private const val PROJECTIVE_MATCH_SIDE_FRACTION = 0.15
        private const val MIN_PROJECTIVE_MATCH_PX = 16.0

        private fun qrDimension(version: Int): Int {
            require(version in 1..40) { "invalid QR version $version" }
            return 17 + 4 * version
        }

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
