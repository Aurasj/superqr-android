package com.superqr.android.ui.v6

import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import androidx.compose.ui.geometry.Offset
import com.superqr.android.vision.v6.model.V6StaticResult

/**
 * Maps detector-space geometry into PreviewView coordinates using CameraX's
 * official OutputTransform/CoordinateTransform pipeline.
 *
 * Detector coordinates are crop-relative and rotation-normalized. The caller
 * must therefore create the ImageProxy OutputTransform with cropRect and
 * rotationDegrees enabled.
 */
data class V6PreviewOverlayGeometry(
    val outerQuad: List<Offset>,
    val gridSegments: List<Pair<Offset, Offset>>,
    val pilotPoints: List<Offset>,
    val isFresh: Boolean,
)

object V6PreviewOverlayMapper {
    private const val GRID_MIN = 200.0
    private const val GRID_MAX = 800.0
    private const val GRID_STEP = 30.0

    fun map(
        result: V6StaticResult,
        source: OutputTransform,
        target: OutputTransform,
    ): V6PreviewOverlayGeometry? {
        val transform = try {
            CoordinateTransform(source, target)
        } catch (_: Throwable) {
            return null
        }

        val quad = result.detectedQuad ?: return null
        if (quad.size != 4) return null

        val mappedQuad = quad.mapNotNull { point ->
            if (point.size < 2) null else mapPoint(transform, point[0], point[1])
        }
        if (mappedQuad.size != 4) return null

        val src = result.diagnosticPayload?.classificationSource
        val fresh = src == "FULL_DETECTION" || src == "TRACKED_RESAMPLED"
        val hInv = result.finalInvHomography

        val grid = if (fresh && hInv != null && hInv.size == 9) {
            buildGridSegments(hInv, transform)
        } else {
            emptyList()
        }

        val pilots = if (fresh) {
            result.diagnosticPayload?.pilotDetails.orEmpty().mapNotNull { pilot ->
                mapPoint(transform, pilot.mappedCameraCenterX, pilot.mappedCameraCenterY)
            }
        } else {
            emptyList()
        }

        return V6PreviewOverlayGeometry(
            outerQuad = mappedQuad,
            gridSegments = grid,
            pilotPoints = pilots,
            isFresh = fresh,
        )
    }

    private fun buildGridSegments(
        canonicalToDetector: DoubleArray,
        transform: CoordinateTransform,
    ): List<Pair<Offset, Offset>> {
        val segments = ArrayList<Pair<Offset, Offset>>(42)
        for (i in 0..20) {
            val c = GRID_MIN + i * GRID_STEP

            val v1 = mapHomography(canonicalToDetector, c, GRID_MIN)
            val v2 = mapHomography(canonicalToDetector, c, GRID_MAX)
            if (v1 != null && v2 != null) {
                val p1 = mapPoint(transform, v1.first, v1.second)
                val p2 = mapPoint(transform, v2.first, v2.second)
                if (p1 != null && p2 != null) segments.add(p1 to p2)
            }

            val h1 = mapHomography(canonicalToDetector, GRID_MIN, c)
            val h2 = mapHomography(canonicalToDetector, GRID_MAX, c)
            if (h1 != null && h2 != null) {
                val p1 = mapPoint(transform, h1.first, h1.second)
                val p2 = mapPoint(transform, h2.first, h2.second)
                if (p1 != null && p2 != null) segments.add(p1 to p2)
            }
        }
        return segments
    }

    private fun mapHomography(
        h: DoubleArray,
        x: Double,
        y: Double,
    ): Pair<Double, Double>? {
        val den = h[6] * x + h[7] * y + h[8]
        if (!den.isFinite() || kotlin.math.abs(den) < 1e-9) return null
        val outX = (h[0] * x + h[1] * y + h[2]) / den
        val outY = (h[3] * x + h[4] * y + h[5]) / den
        if (!outX.isFinite() || !outY.isFinite()) return null
        return outX to outY
    }

    private fun mapPoint(
        transform: CoordinateTransform,
        x: Double,
        y: Double,
    ): Offset? {
        if (!x.isFinite() || !y.isFinite()) return null
        val points = floatArrayOf(x.toFloat(), y.toFloat())
        return try {
            transform.mapPoints(points)
            if (points[0].isFinite() && points[1].isFinite()) {
                Offset(points[0], points[1])
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
