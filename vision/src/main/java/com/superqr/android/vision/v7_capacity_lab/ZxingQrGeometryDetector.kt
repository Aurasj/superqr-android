package com.superqr.android.vision.v7_capacity_lab

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.detector.Detector

/**
 * Perspective-correct QR outer quadrangle derived from finder patterns plus the
 * bottom-right alignment pattern.
 *
 * ZXing's QR detector uses TL/TR/BL finder centers to estimate the QR dimension,
 * searches near the provisional bottom-right for an alignment pattern, and then
 * builds a projective transform. We require that alignment pattern here: if it is
 * absent we deliberately return null instead of inventing BR with an affine
 * parallelogram approximation, which is wrong for a tilted screen/phone.
 */
internal class ZxingQrGeometryDetector {

    fun detectOuterQuad(luma: ByteArray, width: Int, height: Int): List<DoubleArray>? {
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
            val result = Detector(bitmap.blackMatrix).detect(
                mapOf(DecodeHintType.TRY_HARDER to true),
            )
            val dimension = result.bits.width
            val points = result.points

            // ZXing DetectorResult ordering for QR is BL, TL, TR, and optionally
            // the bottom-right alignment pattern. Perspective-correct BR needs
            // that fourth point; three finder centers alone are insufficient.
            if (dimension <= 0 || points.size < 4) return null
            val bottomLeft = points[0]
            val topLeft = points[1]
            val topRight = points[2]
            val alignment = points[3]
            projectOuterQuad(dimension, topLeft, topRight, bottomLeft, alignment)
        } catch (_: Throwable) {
            null
        }
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

        // Finder-center coordinates above live in the QR module coordinate system.
        // Project the module-grid boundary (0..dimension), not the quiet zone.
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
        if (quad.flatten().any { !it.isFinite() }) return null
        return quad
    }
}
