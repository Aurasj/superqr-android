package com.superqr.android.camera

import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform

/**
 * Explicit coordinate mapping between ImageAnalysis and PreviewView.
 *
 * ImageAnalysis frames arrive in sensor coordinates (with crop + rotation
 * applied via [ImageProxyTransformFactory]). PreviewView renders into its own
 * coordinate space determined by the view size and scale type.
 *
 * CameraX [CoordinateTransform] bridges the two spaces. Create the source
 * transform from the ImageProxy (with crop-rect and rotation enabled) and the
 * target transform from [PreviewView.getOutputTransform].
 *
 * Usage:
 * ```
 * val sourceTx = transformFactory.getOutputTransform(imageProxy)
 * val targetTx = previewView.outputTransform
 * val previewPoint = CoordinateMapper.mapPoint(sourceTx, targetTx, analysisX, analysisY)
 * ```
 */
object CoordinateMapper {

    /**
     * Map a single point from analysis-image coordinates to PreviewView coordinates.
     * Returns null if coordinate transforms are not available or the mapped point
     * is non-finite.
     */
    fun mapPoint(
        source: OutputTransform,
        target: OutputTransform,
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        if (!x.isFinite() || !y.isFinite()) return null
        val transform = try {
            CoordinateTransform(source, target)
        } catch (_: Throwable) {
            return null
        }
        val points = floatArrayOf(x, y)
        return try {
            transform.mapPoints(points)
            if (points[0].isFinite() && points[1].isFinite()) {
                points[0] to points[1]
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Map an array of points from analysis-image coordinates to PreviewView
     * coordinates in-place. Returns true if all points mapped successfully.
     */
    fun mapPoints(
        source: OutputTransform,
        target: OutputTransform,
        points: FloatArray,
    ): Boolean {
        val transform = try {
            CoordinateTransform(source, target)
        } catch (_: Throwable) {
            return false
        }
        return try {
            transform.mapPoints(points)
            points.all { it.isFinite() }
        } catch (_: Throwable) {
            false
        }
    }
}
