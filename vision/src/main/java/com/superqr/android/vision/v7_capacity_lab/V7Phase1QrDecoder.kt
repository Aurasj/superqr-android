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
 * Overlay geometry intentionally trusts the three finder-anchored QR corners
 * (TL/TR/BL) and does not trust OpenCV's raw bottom-right estimate. The visible
 * BR is reconstructed from those three anchors. On the first observation it is
 * the affine/parallelogram completion TR + BL - TL; on following observations
 * the previous BR is transported by the affine transform defined by the three
 * current finder anchors. This keeps a static BR fixed while still following
 * real phone translation/scale/rotation immediately. Raw OpenCV geometry remains
 * untouched for actual QR decoding.
 *
 * Dense V27/V40 camera images also get a bounded QRCodeDetectorAruco fallback.
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

    /** Presentation/guidance geometry; never fed back into QR decoding. */
    private var stableQuad: List<DoubleArray>? = null

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

    /** Returns decoded payload plus finder-anchor-derived display geometry. */
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

        // Reuse trusted decode geometry only while it keeps decoding. If it misses,
        // reacquire immediately rather than showing stale points while the phone moves.
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
        stableQuad = null
        return ByteArray(0) to null
    }

    private fun chooseCandidateQuad(
        standard: List<DoubleArray>?,
        fallback: List<DoubleArray>?,
    ): List<DoubleArray>? = when {
        standard == null -> fallback
        fallback == null -> standard
        stableQuad == null -> standard
        // Ignore BR when comparing detector candidates; TL/TR/BL are the three
        // QR finder-anchored corners and are the trustworthy geometric evidence.
        anchorDistance(standard, checkNotNull(stableQuad)) <=
            anchorDistance(fallback, checkNotNull(stableQuad)) -> standard
        else -> fallback
    }

    private fun stabilizeQuad(raw: List<DoubleArray>?): List<DoubleArray>? {
        if (raw == null || raw.size != 4) {
            stableQuad = null
            return null
        }

        // OpenCV QR ordering is TL, TR, BR, BL. Surface the three real finder
        // anchors immediately; only BR is reconstructed for presentation.
        val observed = raw.map { doubleArrayOf(it[0], it[1]) }
        val previous = stableQuad
        val reconstructedBr = if (previous == null || previous.size != 4) {
            parallelogramBottomRight(observed)
        } else {
            transportBottomRight(previous, observed) ?: parallelogramBottomRight(observed)
        }

        val next = listOf(
            doubleArrayOf(observed[0][0], observed[0][1]),
            doubleArrayOf(observed[1][0], observed[1][1]),
            reconstructedBr,
            doubleArrayOf(observed[3][0], observed[3][1]),
        )
        stableQuad = next
        return next
    }

    /**
     * Affine coordinates of previous BR relative to previous TL/TR/BL are
     * transported onto the current TL/TR/BL. This follows real motion from the
     * finder anchors without consulting the noisy current raw BR estimate.
     */
    private fun transportBottomRight(
        previous: List<DoubleArray>,
        current: List<DoubleArray>,
    ): DoubleArray? {
        if (previous.size != 4 || current.size != 4) return null
        val a = previous[0]
        val b = previous[1]
        val c = previous[3]
        val p = previous[2]

        val bx = b[0] - a[0]
        val by = b[1] - a[1]
        val cx = c[0] - a[0]
        val cy = c[1] - a[1]
        val px = p[0] - a[0]
        val py = p[1] - a[1]
        val det = bx * cy - by * cx
        if (!det.isFinite() || abs(det) < 1e-6) return null

        val u = (px * cy - py * cx) / det
        val v = (bx * py - by * px) / det
        if (!u.isFinite() || !v.isFinite()) return null

        val na = current[0]
        val nb = current[1]
        val nc = current[3]
        val nbx = nb[0] - na[0]
        val nby = nb[1] - na[1]
        val ncx = nc[0] - na[0]
        val ncy = nc[1] - na[1]
        val x = na[0] + u * nbx + v * ncx
        val y = na[1] + u * nby + v * ncy
        if (!x.isFinite() || !y.isFinite()) return null
        return doubleArrayOf(x, y)
    }

    private fun parallelogramBottomRight(quad: List<DoubleArray>): DoubleArray {
        val tl = quad[0]
        val tr = quad[1]
        val bl = quad[3]
        return doubleArrayOf(
            tr[0] + bl[0] - tl[0],
            tr[1] + bl[1] - tl[1],
        )
    }

    private fun pointDistance(a: DoubleArray, b: DoubleArray): Double =
        hypot(a[0] - b[0], a[1] - b[1])

    private fun anchorDistance(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.size != 4 || b.size != 4) return Double.POSITIVE_INFINITY
        val anchors = intArrayOf(0, 1, 3)
        return anchors.sumOf { index -> pointDistance(a[index], b[index]) } / anchors.size.toDouble()
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
    }

    companion object {
        // One failed tracked decode immediately falls back to a fresh detector pass.
        private const val MAX_TRUSTED_DECODE_MISSES = 1

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
