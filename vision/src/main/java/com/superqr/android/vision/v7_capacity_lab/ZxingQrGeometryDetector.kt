package com.superqr.android.vision.v7_capacity_lab

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector

internal data class ZxingQrAnalysis(
    val payload: ByteArray,
    val outerQuad: List<DoubleArray>?,
    val stage: String,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val dimension: Int? = null,
    val pointCount: Int = 0,
    val detectMs: Double? = null,
    val decodeMs: Double? = null,
    val totalMs: Double = 0.0,
)

/**
 * One bounded ZXing pass for dense QR controls.
 *
 * The detector resolves finder patterns plus the bottom-right alignment pattern,
 * then ZXing's QR decoder consumes the already sampled BitMatrix. This means the
 * same expensive pass can provide both perspective-correct presentation geometry
 * and a binary-safe payload fallback for V27/V40.
 *
 * Payload extraction deliberately uses DecoderResult.byteSegments. QRCodeReader's
 * rawBytes are the corrected QR data-codeword stream (which also contains mode,
 * length and padding bits); byteSegments are the actual BYTE-mode payload bytes
 * emitted by DecodedBitStreamParser.
 *
 * Phase 0 diagnostics deliberately report the exact stage/exception and timings;
 * they do not alter detector or decoder behavior.
 */
internal class ZxingQrGeometryDetector {
    private val decoder = Decoder()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun analyze(luma: ByteArray, width: Int, height: Int): ZxingQrAnalysis? {
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

        val detectStartNs = System.nanoTime()
        val detectorResult = try {
            Detector(bitmap.blackMatrix).detect(hints)
        } catch (t: Throwable) {
            return failure(
                stage = "DETECT_FAIL",
                throwable = t,
                totalStartNs = totalStartNs,
                detectMs = elapsedMs(detectStartNs),
            )
        }
        val detectMs = elapsedMs(detectStartNs)
        val dimension = detectorResult.bits.width
        val points = detectorResult.points

        val outerQuad = if (dimension > 0 && points.size >= 4) {
            projectOuterQuad(
                dimension = dimension,
                topLeft = points[1],
                topRight = points[2],
                bottomLeft = points[0],
                alignment = points[3],
            )
        } else {
            null
        }

        val decodeStartNs = System.nanoTime()
        return try {
            val decoded = decoder.decode(detectorResult.bits, hints)
            val payload = concatenateByteSegments(decoded.byteSegments)
            ZxingQrAnalysis(
                payload = payload,
                outerQuad = outerQuad,
                stage = "DECODE_OK",
                dimension = dimension,
                pointCount = points.size,
                detectMs = detectMs,
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
                dimension = dimension,
                pointCount = points.size,
                detectMs = detectMs,
                decodeMs = elapsedMs(decodeStartNs),
                totalMs = elapsedMs(totalStartNs),
            )
        }
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
}
