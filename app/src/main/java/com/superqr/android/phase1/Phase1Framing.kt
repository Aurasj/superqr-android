package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquisitionResult
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrResult
import kotlin.math.max

data class Phase1FramePoint(val x: Float, val y: Float)

enum class Phase1FramingMode { GRID, QR }

enum class Phase1FramingStatus(val label: String) {
    ALIGNING("ALIGNING CAMERA"),
    GOOD("FRAMING GOOD"),
    TOO_SMALL("MOVE CLOSER"),
    PERSPECTIVE_SKEWED("HOLD PHONE MORE PARALLEL"),
    MOVE_BACK("MOVE PHONE BACK"),
    NOT_FOUND("CARRIER NOT FOUND"),
    QR_SEARCHING("QR SEARCHING"),
    QR_DETECTED("QR DETECTED"),
    QR_GOOD("QR FRAMING GOOD"),
}

enum class Phase1TrackingState(val label: String) {
    SEARCHING("SEARCHING"),
    ACQUIRED("ACQUIRED"),
    TRACKING("TRACKING"),
    HOLDING("HOLDING (brief miss)"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromSource(source: String): Phase1TrackingState = when {
            source == "V7_SYNC_TRACKED" || source == "V7_OPTICAL_FLOW_TRACKED" -> TRACKING
            source == "V7_TRACK_HOLD" -> HOLDING
            source.endsWith("_ACQUIRED") -> ACQUIRED
            source == "QR_NATIVE_LOCKED" -> TRACKING
            source == "QR_NATIVE_SEARCH" -> SEARCHING
            source == "NONE" -> UNKNOWN
            else -> SEARCHING
        }
    }
}

data class Phase1FramingGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val status: Phase1FramingStatus,
    val mode: Phase1FramingMode = Phase1FramingMode.GRID,
    val finderCenters: List<Phase1FramePoint> = emptyList(),
    val finderQuads: List<List<Phase1FramePoint>> = emptyList(),
    val carrierQuad: List<Phase1FramePoint>? = null,
    val candidateQuad: List<Phase1FramePoint>? = null,
    val safeInsetPx: Float = 0f,
    val minimumMarginPx: Float? = null,
    val detail: String = "Waiting for an analyzed frame",
    val source: String = "NONE",
    val clipped: Boolean = false,
    val sizeFraction: Float? = null,
    val skew: Float? = null,
) {
    val visibleFinders: Int
        get() = finderCenters.size.coerceAtMost(4)

    val trackingState: Phase1TrackingState
        get() = Phase1TrackingState.fromSource(source)

    companion object {
        fun empty(): Phase1FramingGeometry =
            Phase1FramingGeometry(0, 0, Phase1FramingStatus.ALIGNING)
    }
}

/** Operator guidance derived from the same normalized ImageAnalysis coordinates consumed by vision. */
object Phase1FramingEvaluator {
    private const val SAFE_INSET_FRACTION = 0.035f
    private const val MIN_SAFE_INSET_PX = 20f
    private const val MIN_SIZE_FRACTION = 0.18f
    private const val MAX_SKEW = 0.22f

    fun evaluate(
        frameWidth: Int,
        frameHeight: Int,
        acquisition: V7CarrierAcquisitionResult?,
        spec: V7CarrierSpec,
    ): Phase1FramingGeometry {
        if (frameWidth <= 0 || frameHeight <= 0) return Phase1FramingGeometry.empty()
        val safeInset = safeInset(frameWidth, frameHeight)
        if (acquisition == null) {
            return Phase1FramingGeometry(
                frameWidth, frameHeight, Phase1FramingStatus.NOT_FOUND,
                safeInsetPx = safeInset,
                detail = "Show the complete Desktop carrier inside the analysis frame",
            )
        }

        val homography = acquisition.canonicalToImageHomography
        val centers = acquisition.finderCenters.mapNotNull(::diagnosticPoint)
        val candidate = acquisition.detectedQuad?.mapNotNull(::diagnosticPoint)?.takeIf { it.size == 4 }
        if (homography != null) {
            val finderQuads = spec.finderOuterBboxes.mapNotNull { bbox -> projectBbox(homography, bbox) }
            val carrierQuad = projectBbox(homography, spec.borderBbox)
            val allFinderPoints = finderQuads.flatten()
            val minimumMargin = allFinderPoints.minOfOrNull { point ->
                minOf(point.x, point.y, frameWidth - 1f - point.x, frameHeight - 1f - point.y)
            }
            val visibleFinderQuads = finderQuads.filter { quad ->
                quad.all { point -> point.x in 0f..(frameWidth - 1f) && point.y in 0f..(frameHeight - 1f) }
            }
            val projectedVisibleFinders = visibleFinderQuads.size
            val clipped = projectedVisibleFinders < 4
            val safelyFramed = finderQuads.size == 4 && projectedVisibleFinders == 4 &&
                minimumMargin != null && minimumMargin >= safeInset
            val sizeFraction = carrierQuad?.let { quadSizeFraction(it, frameWidth, frameHeight) }
            val skew = carrierQuad?.let(::quadSkew)
            val tooSmall = sizeFraction != null && sizeFraction < MIN_SIZE_FRACTION
            val skewed = skew != null && skew > MAX_SKEW
            val status = when {
                !safelyFramed -> Phase1FramingStatus.MOVE_BACK
                tooSmall -> Phase1FramingStatus.TOO_SMALL
                skewed -> Phase1FramingStatus.PERSPECTIVE_SKEWED
                else -> Phase1FramingStatus.GOOD
            }
            return Phase1FramingGeometry(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                status = status,
                finderCenters = visibleFinderQuads.map(::center),
                finderQuads = finderQuads,
                carrierQuad = carrierQuad,
                candidateQuad = candidate,
                safeInsetPx = safeInset,
                minimumMarginPx = minimumMargin,
                clipped = clipped,
                sizeFraction = sizeFraction,
                skew = skew,
                detail = when {
                    !safelyFramed && projectedVisibleFinders < 4 ->
                        "Only $projectedVisibleFinders of 4 finder squares are inside the analysis frame (clipped)"
                    !safelyFramed -> "Keep all 4 finder squares inside the safe area"
                    tooSmall -> "Marker is small in frame; move the phone closer"
                    skewed -> "Viewing angle is skewed; hold the phone more parallel to the screen"
                    else -> "All 4 finders visible with a safe edge margin"
                },
                source = acquisition.source,
            )
        }

        val hasPartialCarrier = centers.isNotEmpty() || acquisition.finderHypothesisCount > 0 ||
            acquisition.carrierLike || acquisition.bestSyncStatus == "V7_NO_COMPLETE_CARRIER_GEOMETRY"
        return Phase1FramingGeometry(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            status = if (hasPartialCarrier) Phase1FramingStatus.MOVE_BACK else Phase1FramingStatus.NOT_FOUND,
            finderCenters = centers.take(4),
            candidateQuad = candidate,
            safeInsetPx = safeInset,
            detail = if (hasPartialCarrier) {
                "Only ${centers.size.coerceAtMost(4)} of 4 finder positions are safely visible"
            } else {
                "Center the complete Desktop carrier in this frame"
            },
            source = acquisition.source,
        )
    }

    fun evaluateQr(frameWidth: Int, frameHeight: Int, qr: V7Phase1QrResult): Phase1FramingGeometry {
        if (frameWidth <= 0 || frameHeight <= 0) return Phase1FramingGeometry.empty()
        val valid = qr.valid && qr.envelope != null
        val quad = qr.quad?.mapNotNull(::diagnosticPoint)?.takeIf { it.size == 4 }
        val hasQrEvidence = quad != null || qr.decoded
        val safeInset = safeInset(frameWidth, frameHeight)
        val sizeFraction = quad?.let { quadSizeFraction(it, frameWidth, frameHeight) }
        val skew = quad?.let(::quadSkew)
        val clipped = quad?.any { point ->
            point.x < 0f || point.y < 0f || point.x > frameWidth - 1f || point.y > frameHeight - 1f
        } ?: false
        val tooSmall = !valid && sizeFraction != null && sizeFraction < MIN_SIZE_FRACTION
        val skewed = !valid && skew != null && skew > MAX_SKEW
        val status = when {
            valid -> Phase1FramingStatus.QR_GOOD
            clipped -> Phase1FramingStatus.MOVE_BACK
            tooSmall -> Phase1FramingStatus.TOO_SMALL
            skewed -> Phase1FramingStatus.PERSPECTIVE_SKEWED
            hasQrEvidence -> Phase1FramingStatus.QR_DETECTED
            else -> Phase1FramingStatus.QR_SEARCHING
        }
        return Phase1FramingGeometry(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            status = status,
            mode = Phase1FramingMode.QR,
            candidateQuad = quad,
            safeInsetPx = safeInset,
            clipped = clipped,
            sizeFraction = sizeFraction,
            skew = skew,
            detail = when {
                valid -> "QR decoded from this exact full ImageAnalysis frame"
                clipped -> "Keep the complete QR inside the camera frame"
                tooSmall -> "QR marker is small in frame; move the phone closer"
                skewed -> "Viewing angle is skewed; hold the phone more parallel to the screen"
                qr.decoded -> "QR detected but payload validation failed: ${qr.failure ?: "invalid payload"}"
                quad != null -> "QR geometry detected; decoding the dense payload"
                else -> "Keep the complete QR inside the frame; OpenCV scans this full analysis image"
            },
            source = if (valid) "QR_NATIVE_LOCKED" else if (hasQrEvidence) "QR_NATIVE_DETECTED" else "QR_NATIVE_SEARCH",
        )
    }

    private fun safeInset(frameWidth: Int, frameHeight: Int): Float =
        max(MIN_SAFE_INSET_PX, minOf(frameWidth, frameHeight) * SAFE_INSET_FRACTION)

    private fun quadSizeFraction(
        quad: List<Phase1FramePoint>,
        frameWidth: Int,
        frameHeight: Int,
    ): Float? {
        if (quad.size != 4) return null
        val sides = FloatArray(4) { index ->
            val a = quad[index]
            val b = quad[(index + 1) % 4]
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        }
        val shortest = sides.minOrNull() ?: return null
        val frameShortEdge = minOf(frameWidth, frameHeight).toFloat()
        if (frameShortEdge <= 0f) return null
        return shortest / frameShortEdge
    }

    private fun quadSkew(quad: List<Phase1FramePoint>): Float? {
        if (quad.size != 4) return null
        val sides = FloatArray(4) { index ->
            val a = quad[index]
            val b = quad[(index + 1) % 4]
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        }
        if (sides.any { it <= 0f }) return null
        val horizontalBalance = minOf(sides[0], sides[2]) / maxOf(sides[0], sides[2])
        val verticalBalance = minOf(sides[1], sides[3]) / maxOf(sides[1], sides[3])
        val diagonalA = kotlin.math.hypot(
            (quad[0].x - quad[2].x).toDouble(),
            (quad[0].y - quad[2].y).toDouble(),
        ).toFloat()
        val diagonalB = kotlin.math.hypot(
            (quad[1].x - quad[3].x).toDouble(),
            (quad[1].y - quad[3].y).toDouble(),
        ).toFloat()
        val diagonalBalance = if (diagonalA <= 0f || diagonalB <= 0f) {
            1f
        } else {
            minOf(diagonalA, diagonalB) / maxOf(diagonalA, diagonalB)
        }
        return (1f - horizontalBalance * verticalBalance * diagonalBalance).coerceIn(0f, 1f)
    }

    private fun diagnosticPoint(values: DoubleArray): Phase1FramePoint? {
        if (values.size < 2 || !values[0].isFinite() || !values[1].isFinite()) return null
        return Phase1FramePoint(values[0].toFloat(), values[1].toFloat())
    }

    private fun projectBbox(h: DoubleArray, bbox: DoubleArray): List<Phase1FramePoint>? {
        if (h.size < 9 || bbox.size < 4) return null
        val canonical = arrayOf(
            bbox[0] to bbox[1],
            bbox[2] to bbox[1],
            bbox[2] to bbox[3],
            bbox[0] to bbox[3],
        )
        return canonical.map { (x, y) -> project(h, x, y) ?: return null }
    }

    private fun project(h: DoubleArray, x: Double, y: Double): Phase1FramePoint? {
        val denominator = h[6] * x + h[7] * y + h[8]
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-9) return null
        val px = (h[0] * x + h[1] * y + h[2]) / denominator
        val py = (h[3] * x + h[4] * y + h[5]) / denominator
        if (!px.isFinite() || !py.isFinite()) return null
        return Phase1FramePoint(px.toFloat(), py.toFloat())
    }

    private fun center(points: List<Phase1FramePoint>): Phase1FramePoint = Phase1FramePoint(
        points.sumOf { it.x.toDouble() }.toFloat() / points.size,
        points.sumOf { it.y.toDouble() }.toFloat() / points.size,
    )
}
