package com.superqr.android.vision.v7_capacity_lab

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.GridSampler
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector
import kotlin.math.abs

internal data class ZxingQrAnalysis(
    val payload: ByteArray,
    val outerQuad: List<DoubleArray>?,
    val stage: String,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    /** Dimension inferred by ZXing's detector before the Phase 0 known-profile correction. */
    val dimension: Int? = null,
    /** Dimension actually sampled and passed to ZXing's QR decoder. */
    val sampledDimension: Int? = null,
    val forcedDimension: Boolean = false,
    val pointCount: Int = 0,
    val detectMs: Double? = null,
    val sampleMs: Double? = null,
    val decodeMs: Double? = null,
    val totalMs: Double = 0.0,
)

/**
 * One bounded ZXing pass for dense QR controls.
 *
 * Finder/alignment detection remains native ZXing. Phase 1, however, already knows
 * the finite QR control versions from its manifest. Dense camera frames can make
 * ZXing infer an adjacent version (for example 121 modules instead of V27's 125),
 * so the detected finder/alignment geometry is re-sampled at the closest known
 * control dimension before decoding. This is a sampling correction, not a new wire
 * contract and not a synthetic presentation corner.
 *
 * Payload extraction deliberately uses DecoderResult.byteSegments. QRCodeReader's
 * rawBytes are the corrected QR data-codeword stream (which also contains mode,
 * length and padding bits); byteSegments are the actual BYTE-mode payload bytes
 * emitted by DecodedBitStreamParser.
 */
internal class ZxingQrGeometryDetector {
    private val decoder = Decoder()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        expectedDimensions: IntArray = intArrayOf(),
    ): ZxingQrAnalysis? {
        val totalStartNs = System.nanoTime()
        if (width <= 0 || height <= 0 || luma.size < width * height) {
            return ZxingQrAnalysis(
                payload = ByteArray(0),
                outerQuad = null,
                stage = "INPUT_INVALID",
                totalMs = elapsedMs(totalStartNs),
            )
        }

        val bitmap = try {
            val source = PlanarYUVLuminanceSource(
                luma,
                width,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            BinaryBitmap(HybridBinarizer(source))
        } catch (t: Throwable) {
            return failure("BINARIZE_FAIL", t, totalStartNs)
        }

        val blackMatrix = try {
            bitmap.blackMatrix
        } catch (t: Throwable) {
            return failure("BINARIZE_FAIL", t, totalStartNs)
        }

        val detectStartNs = System.nanoTime()
        val detectorResult = try {
            Detector(blackMatrix).detect(hints)
        } catch (t: Throwable) {
            return failure(
                stage = "DETECT_FAIL",
                throwable = t,
                totalStartNs = totalStartNs,
                detectMs = elapsedMs(detectStartNs),
            )
        }
        val detectMs = elapsedMs(detectStartNs)
        val detectedDimension = detectorResult.bits.width
        val points = detectorResult.points

        val outerQuad = if (detectedDimension > 0 && points.size >= 4) {
            projectOuterQuad(
                dimension = detectedDimension,
                topLeft = points[1],
                topRight = points[2],
                bottomLeft = points[0],
                alignment = points[3],
            )
        } else {
            null
        }

        val forcedDimension = closestExpectedDimension(detectedDimension, expectedDimensions)
        val sampleStartNs = System.nanoTime()
        val sampledBits = if (forcedDimension != null && forcedDimension != detectedDimension && points.size >= 3) {
            try {
                sampleKnownDimension(
                    image = blackMatrix,
                    dimension = forcedDimension,
                    points = points,
                )
            } catch (t: Throwable) {
                return ZxingQrAnalysis(
                    payload = ByteArray(0),
                    outerQuad = outerQuad,
                    stage = "FORCED_SAMPLE_FAIL",
                    exceptionType = t.javaClass.simpleName,
                    exceptionMessage = t.message,
                    dimension = detectedDimension,
                    sampledDimension = forcedDimension,
                    forcedDimension = true,
                    pointCount = points.size,
                    detectMs = detectMs,
                    sampleMs = elapsedMs(sampleStartNs),
                    totalMs = elapsedMs(totalStartNs),
                )
            }
        } else {
            detectorResult.bits
        }
        val sampledDimension = sampledBits.width
        val wasForced = sampledDimension != detectedDimension
        val sampleMs = elapsedMs(sampleStartNs)

        val decodeStartNs = System.nanoTime()
        return try {
            val decoded = decoder.decode(sampledBits, hints)
            val payload = concatenateByteSegments(decoded.byteSegments)
            ZxingQrAnalysis(
                payload = payload,
                outerQuad = outerQuad,
                stage = "DECODE_OK",
                dimension = detectedDimension,
                sampledDimension = sampledDimension,
                forcedDimension = wasForced,
                pointCount = points.size,
                detectMs = detectMs,
                sampleMs = sampleMs,
                decodeMs = elapsedMs(decodeStartNs),
                totalMs = elapsedMs(totalStartNs),
            )
        } catch (t: Throwable) {
            ZxingQrAnalysis(
                payload = ByteArray(0),
                outerQuad = outerQuad,
                stage = "DECODE_FAIL",
                exceptionType = t.javaClass.simpleName,
                exceptionMessage = t.message,
                dimension = detectedDimension,
                sampledDimension = sampledDimension,
                forcedDimension = wasForced,
                pointCount = points.size,
                detectMs = detectMs,
                sampleMs = sampleMs,
                decodeMs = elapsedMs(decodeStartNs),
                totalMs = elapsedMs(totalStartNs),
            )
        }
    }

    /**
     * Reuses ZXing's measured finder/alignment locations but replaces only the
     * detector-inferred module count. The transform mirrors Detector.createTransform.
     */
    private fun sampleKnownDimension(
        image: com.google.zxing.common.BitMatrix,
        dimension: Int,
        points: Array<ResultPoint>,
    ): com.google.zxing.common.BitMatrix {
        require(dimension >= 21 && dimension % 4 == 1) { "invalid QR dimension $dimension" }
        require(points.size >= 3) { "finder points missing" }

        val bottomLeft = points[0]
        val topLeft = points[1]
        val topRight = points[2]
        val alignment = points.getOrNull(3)
        val dimMinusThree = dimension - 3.5f

        val bottomRightX: Float
        val bottomRightY: Float
        val sourceBottomRightX: Float
        val sourceBottomRightY: Float
        if (alignment != null) {
            bottomRightX = alignment.x
            bottomRightY = alignment.y
            sourceBottomRightX = dimMinusThree - 3.0f
            sourceBottomRightY = sourceBottomRightX
        } else {
            bottomRightX = topRight.x - topLeft.x + bottomLeft.x
            bottomRightY = topRight.y - topLeft.y + bottomLeft.y
            sourceBottomRightX = dimMinusThree
            sourceBottomRightY = dimMinusThree
        }

        val transform = PerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f,
            3.5f,
            dimMinusThree,
            3.5f,
            sourceBottomRightX,
            sourceBottomRightY,
            3.5f,
            dimMinusThree,
            topLeft.x,
            topLeft.y,
            topRight.x,
            topRight.y,
            bottomRightX,
            bottomRightY,
            bottomLeft.x,
            bottomLeft.y,
        )
        return GridSampler.getInstance().sampleGrid(image, dimension, dimension, transform)
    }

    private fun closestExpectedDimension(detected: Int, expected: IntArray): Int? {
        val candidates = expected
            .filter { it >= 21 && it % 4 == 1 }
            .distinct()
        if (candidates.isEmpty()) return null
        val closest = candidates.minByOrNull { abs(it - detected) } ?: return null
        // V27 (125) and V40 (177) are far apart. A narrow bound corrects adjacent
        // version mistakes without forcing an unrelated symbol into a known profile.
        return closest.takeIf { abs(it - detected) <= MAX_FORCED_DIMENSION_DELTA }
    }

    private fun failure(
        stage: String,
        throwable: Throwable,
        totalStartNs: Long,
        detectMs: Double? = null,
    ): ZxingQrAnalysis = ZxingQrAnalysis(
        payload = ByteArray(0),
        outerQuad = null,
        stage = stage,
        exceptionType = throwable.javaClass.simpleName,
        exceptionMessage = throwable.message,
        detectMs = detectMs,
        totalMs = elapsedMs(totalStartNs),
    )

    private fun elapsedMs(startNs: Long): Double =
        (System.nanoTime() - startNs) / 1_000_000.0

    private fun concatenateByteSegments(segments: List<ByteArray>?): ByteArray {
        if (segments.isNullOrEmpty()) return ByteArray(0)
        if (segments.size == 1) return segments[0].copyOf()

        val totalBytes = segments.sumOf { it.size }
        val payload = ByteArray(totalBytes)
        var offset = 0
        for (segment in segments) {
            segment.copyInto(payload, destinationOffset = offset)
            offset += segment.size
        }
        return payload
    }

    private fun projectOuterQuad(
        dimension: Int,
        topLeft: ResultPoint,
        topRight: ResultPoint,
        bottomLeft: ResultPoint,
        alignment: ResultPoint,
    ): List<DoubleArray>? {
        // Same canonical coordinates used by ZXing Detector.createTransform().
        val dimMinusThree = dimension - 3.5f
        val sourceAlignment = dimMinusThree - 3.0f
        if (sourceAlignment <= 0f) return null

        val transform = PerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f,
            3.5f,
            dimMinusThree,
            3.5f,
            sourceAlignment,
            sourceAlignment,
            3.5f,
            dimMinusThree,
            topLeft.x,
            topLeft.y,
            topRight.x,
            topRight.y,
            alignment.x,
            alignment.y,
            bottomLeft.x,
            bottomLeft.y,
        )

        // Project the QR module-grid boundary (quiet zone excluded).
        val corners = floatArrayOf(
            0f, 0f,
            dimension.toFloat(), 0f,
            dimension.toFloat(), dimension.toFloat(),
            0f, dimension.toFloat(),
        )
        transform.transformPoints(corners)

        val quad = List(4) { index ->
            doubleArrayOf(
                corners[index * 2].toDouble(),
                corners[index * 2 + 1].toDouble(),
            )
        }
        if (quad.any { point -> point.any { coordinate -> !coordinate.isFinite() } }) return null
        return quad
    }

    companion object {
        private const val MAX_FORCED_DIMENSION_DELTA = 12
    }
}
