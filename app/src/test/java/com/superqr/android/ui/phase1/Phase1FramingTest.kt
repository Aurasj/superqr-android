package com.superqr.android.ui.phase1

import com.superqr.android.vision.v7_capacity_lab.V7CarrierAcquisitionResult
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import com.superqr.android.vision.v7_capacity_lab.V7LabSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1FramingTest {
    private val spec = V7CarrierSpec()

    @Test
    fun fitTransformPreservesEntirePortraitAnalysisFrameWithoutCrop() {
        val fit = Phase1FrameFit.calculate(720, 1280, 1000f, 800f)
        assertEquals(0.625f, fit.scale, 0.0001f)
        assertEquals(450f, fit.renderedWidth, 0.01f)
        assertEquals(800f, fit.renderedHeight, 0.01f)
        assertEquals(275f, fit.offsetX, 0.01f)
        assertEquals(0f, fit.offsetY, 0.01f)
        assertEquals(Phase1FramePoint(275f, 0f), fit.map(Phase1FramePoint(0f, 0f)))
        assertEquals(Phase1FramePoint(725f, 800f), fit.map(Phase1FramePoint(720f, 1280f)))
    }

    @Test
    fun validatedCarrierWithFourSafeFindersIsGood() {
        val result = acquisition(homography(scale = 0.60, offsetX = 60.0, offsetY = 340.0))
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.GOOD, framing.status)
        assertEquals(4, framing.visibleFinders)
        assertEquals(4, framing.finderQuads.size)
        assertTrue(framing.minimumMarginPx!! >= framing.safeInsetPx)
        assertEquals(4, framing.carrierQuad!!.size)
    }

    @Test
    fun validatedHomographyUsesProjectedVisibilityNotTransientContourCount() {
        val result = acquisition(homography(scale = 0.60, offsetX = 60.0, offsetY = 340.0)).copy(
            visibleFinderCount = 1,
            finderCenters = listOf(doubleArrayOf(130.0, 418.0)),
        )
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.GOOD, framing.status)
        assertEquals(4, framing.visibleFinders)
    }

    @Test
    fun validatedCarrierNearFrameEdgeRequiresMovingBack() {
        val result = acquisition(homography(scale = 0.80, offsetX = -55.0, offsetY = 240.0))
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.MOVE_BACK, framing.status)
        assertTrue(framing.visibleFinders < 4 || framing.minimumMarginPx!! < framing.safeInsetPx)
    }

    @Test
    fun finderProjectedOutsideFrameIsFlaggedClipped() {
        // Large enough and offset far enough that a finder corner falls outside
        // the analysis frame entirely, not merely inside an unsafe margin.
        val result = acquisition(homography(scale = 0.95, offsetX = -140.0, offsetY = 100.0))
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.MOVE_BACK, framing.status)
        assertTrue(framing.clipped)
    }

    @Test
    fun tinyValidatedCarrierReportsTooSmall() {
        // A safely-framed but tiny marker (huge margin, small absolute size) should
        // read as "move closer", not "framing good".
        val result = acquisition(homography(scale = 0.05, offsetX = 337.5, offsetY = 617.5))
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.TOO_SMALL, framing.status)
        assertFalse(framing.clipped)
        assertTrue(framing.sizeFraction!! < 0.18f)
    }

    @Test
    fun nonAffineHomographyProducesNonZeroSkew() {
        // A homography with a nonzero perspective term distorts the projected
        // carrier into a non-parallelogram; the skew score must reflect that.
        val skewedHomography = doubleArrayOf(
            0.60, 0.0, 60.0,
            0.0, 0.60, 340.0,
            0.00035, 0.0, 1.0,
        )
        val result = acquisition(skewedHomography)
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertTrue(framing.skew != null && framing.skew!! > 0f)
    }

    @Test
    fun trackingStateDerivesFromAcquisitionSource() {
        assertEquals(Phase1TrackingState.TRACKING, Phase1TrackingState.fromSource("V7_SYNC_TRACKED"))
        assertEquals(Phase1TrackingState.TRACKING, Phase1TrackingState.fromSource("V7_OPTICAL_FLOW_TRACKED"))
        assertEquals(Phase1TrackingState.HOLDING, Phase1TrackingState.fromSource("V7_TRACK_HOLD"))
        assertEquals(Phase1TrackingState.ACQUIRED, Phase1TrackingState.fromSource("V7_FINDERS_ACQUIRED"))
        assertEquals(Phase1TrackingState.SEARCHING, Phase1TrackingState.fromSource("V7_NO_CANDIDATE"))
        assertEquals(Phase1TrackingState.UNKNOWN, Phase1TrackingState.fromSource("NONE"))
    }

    @Test
    fun partialFinderEvidenceNeverClaimsGoodFraming() {
        val result = acquisition(null).copy(
            finderCenters = listOf(
                doubleArrayOf(80.0, 100.0),
                doubleArrayOf(640.0, 100.0),
                doubleArrayOf(640.0, 1100.0),
            ),
            bestSyncStatus = "V7_NO_COMPLETE_CARRIER_GEOMETRY",
            carrierLike = true,
            visibleFinderCount = 3,
        )
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.MOVE_BACK, framing.status)
        assertEquals(3, framing.visibleFinders)
    }

    @Test
    fun noCarrierEvidenceReportsNotFound() {
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, acquisition(null), spec)
        assertEquals(Phase1FramingStatus.NOT_FOUND, framing.status)
        assertEquals(0, framing.visibleFinders)
    }

    @Test
    fun previewGateThrottlesAlignmentAndRejectsEveryMeasuredFrame() {
        val gate = Phase1PreviewGate(intervalNs = 400)
        assertTrue(gate.tryAcquire(1000))
        assertFalse(gate.tryAcquire(2000))
        gate.release()
        assertFalse(gate.tryAcquire(1200))
        assertTrue(gate.tryAcquire(1400))
        gate.release()
        gate.setMeasuring(true)
        repeat(100) { assertFalse(gate.tryAcquire(2000L + it)) }
        gate.setMeasuring(false)
        assertTrue(gate.tryAcquire(2400))
    }

    private fun acquisition(h: DoubleArray?) = V7CarrierAcquisitionResult(
        canonicalToImageHomography = h,
        sync = V7LabSyncResult(null, if (h == null) "V7_NO_QUADRILATERAL" else "LOCKED", -1, -1),
        source = if (h == null) "V7_NO_CANDIDATE" else "V7_FINDERS_ACQUIRED",
        contourCount = 0,
        candidateCount = 0,
        syncAttempts = 0,
        bestSyncStatus = if (h == null) "V7_NO_QUADRILATERAL" else "LOCKED",
        visibleFinderCount = if (h == null) 0 else 4,
    )

    private fun homography(scale: Double, offsetX: Double, offsetY: Double) = doubleArrayOf(
        scale, 0.0, offsetX,
        0.0, scale, offsetY,
        0.0, 0.0, 1.0,
    )
}
