package com.superqr.android.ui.phase1

import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquisitionResult
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import kotlin.math.max

data class Phase1FramePoint(val x: Float, val y: Float)

data class Phase1FitTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val renderedWidth: Float,
    val renderedHeight: Float,
) {
    fun map(point: Phase1FramePoint): Phase1FramePoint = Phase1FramePoint(
        offsetX + point.x * scale,
        offsetY + point.y * scale,
    )
}

/** FIT_CENTER transform: the entire analysis frame is always visible. */
object Phase1FrameFit {
    fun calculate(frameWidth: Int, frameHeight: Int, viewWidth: Float, viewHeight: Float): Phase1FitTransform {
        require(frameWidth > 0 && frameHeight > 0 && viewWidth > 0f && viewHeight > 0f)
        val scale = minOf(viewWidth / frameWidth, viewHeight / frameHeight)
        val renderedWidth = frameWidth * scale
        val renderedHeight = frameHeight * scale
        return Phase1FitTransform(
            scale = scale,
            offsetX = (viewWidth - renderedWidth) * 0.5f,
            offsetY = (viewHeight - renderedHeight) * 0.5f,
            renderedWidth = renderedWidth,
            renderedHeight = renderedHeight,
        )
    }
}

enum class Phase1FramingStatus(val label: String) {
    ALIGNING("ALIGNING CAMERA"),
    GOOD("FRAMING GOOD"),
    MOVE_BACK("MOVE PHONE BACK"),
    NOT_FOUND("CARRIER NOT FOUND"),
}

data class Phase1FramingGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val status: Phase1FramingStatus,
    val finderCenters: List<Phase1FramePoint> = emptyList(),
    val finderQuads: List<List<Phase1FramePoint>> = emptyList(),
    val carrierQuad: List<Phase1FramePoint>? = null,
    val candidateQuad: List<Phase1FramePoint>? = null,
    val safeInsetPx: Float = 0f,
    val minimumMarginPx: Float? = null,
    val detail: String = "Waiting for an analyzed frame",
    val source: String = "NONE",
) {
    val visibleFinders: Int
        get() = finderCenters.size.coerceAtMost(4)

    companion object {
        fun empty(): Phase1FramingGeometry = Phase1FramingGeometry(0, 0, Phase1FramingStatus.ALIGNING)
    }
}

/**
 * Converts acquisition output into operator guidance without changing the
 * acquisition decision. Every point remains in normalized ImageAnalysis luma
 * coordinates, exactly matching [com.superqr.android.camera.LumaFrameBuffer].
 */
object Phase1FramingEvaluator {
    private const val SAFE_INSET_FRACTION = 0.035f
    private const val MIN_SAFE_INSET_PX = 20f

    fun evaluate(
        frameWidth: Int,
        frameHeight: Int,
        acquisition: V7CarrierAcquisitionResult?,
        spec: V7CarrierSpec,
    ): Phase1FramingGeometry {
        if (frameWidth <= 0 || frameHeight <= 0) return Phase1FramingGeometry.empty()
        val safeInset = max(MIN_SAFE_INSET_PX, minOf(frameWidth, frameHeight) * SAFE_INSET_FRACTION)
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
            val allVisible = acquisition.visibleFinderCount >= 4 && finderQuads.size == 4 && allFinderPoints.all {
                it.x in 0f..(frameWidth - 1f) && it.y in 0f..(frameHeight - 1f)
            }
            val safelyFramed = allVisible && minimumMargin != null && minimumMargin >= safeInset
            return Phase1FramingGeometry(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                status = if (safelyFramed) Phase1FramingStatus.GOOD else Phase1FramingStatus.MOVE_BACK,
                finderCenters = finderQuads.take(acquisition.visibleFinderCount.coerceAtMost(4)).map { quad -> center(quad) },
                finderQuads = finderQuads,
                carrierQuad = carrierQuad,
                candidateQuad = candidate,
                safeInsetPx = safeInset,
                minimumMarginPx = minimumMargin,
                detail = if (safelyFramed) {
                    "All 4 finders visible with a safe edge margin"
                } else {
                    if (acquisition.visibleFinderCount < 4) {
                        "Only ${acquisition.visibleFinderCount} of 4 finders are detected"
                    } else {
                        "Keep all 4 finder squares inside the dashed safe area"
                    }
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

    private fun diagnosticPoint(values: DoubleArray): Phase1FramePoint? {
        if (values.size < 2 || !values[0].isFinite() || !values[1].isFinite()) return null
        return Phase1FramePoint(values[0].toFloat(), values[1].toFloat())
    }

    private fun projectBbox(h: DoubleArray, bbox: DoubleArray): List<Phase1FramePoint>? {
        if (h.size < 9 || bbox.size < 4) return null
        val canonical = arrayOf(
            bbox[0] to bbox[1], bbox[2] to bbox[1],
            bbox[2] to bbox[3], bbox[0] to bbox[3],
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

/** Pure capture gate used to prove preview work is absent during measurement. */
class Phase1PreviewGate(private val intervalNs: Long = 400_000_000L) {
    private var lastCaptureNs = Long.MIN_VALUE
    private var inFlight = false
    private var measuring = false

    @Synchronized
    fun setMeasuring(value: Boolean) {
        measuring = value
    }

    @Synchronized
    fun tryAcquire(nowNs: Long): Boolean {
        if (measuring || inFlight) return false
        if (lastCaptureNs != Long.MIN_VALUE && nowNs - lastCaptureNs < intervalNs) return false
        lastCaptureNs = nowNs
        inFlight = true
        return true
    }

    @Synchronized
    fun release() {
        inFlight = false
    }
}
