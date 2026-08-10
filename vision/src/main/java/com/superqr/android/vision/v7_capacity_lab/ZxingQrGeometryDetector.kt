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
 */
internal class ZxingQrGeometryDetector {
    private val decoder = Decoder()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun analyze(luma: ByteArray, width: Int, height: Int): ZxingQrAnalysis? {
        if (width <= 0 || height <= 0 || luma.size < width * height) return null

        return try {
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
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val detectorResult = Detector(bitmap.blackMatrix).detect(hints)
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

            val payload = try {
                val decoded = decoder.decode(detectorResult.bits, hints)
                concatenateByteSegments(decoded.byteSegments)
            } catch (_: Throwable) {
                ByteArray(0)
            }

            ZxingQrAnalysis(payload = payload, outerQuad = outerQuad)
        } catch (_: Throwable) {
            null
        }
    }

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
