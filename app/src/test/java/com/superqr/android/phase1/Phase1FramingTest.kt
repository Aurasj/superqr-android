package com.superqr.android.phase1

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
    fun validatedCarrierWithFourSafeFindersIsGood() {
        val result = acquisition(homography(0.60, 60.0, 340.0))
        val framing = Phase1FramingEvaluator.evaluate(720, 1280, result, spec)
        assertEquals(Phase1FramingStatus.GOOD, framing.status)
        assertEquals(4, framing.visibleFinders)
        assertTrue(framing.minimumMarginPx!! >= framing.safeInsetPx)
    }

    @Test
    fun carrierNearFrameEdgeRequiresMovingBack() {
        val framing = Phase1FramingEvaluator.evaluate(
            720, 1280, acquisition(homography(0.80, -55.0, 240.0)), spec,
        )
        assertEquals(Phase1FramingStatus.MOVE_BACK, framing.status)
    }

    @Test
    fun projectedFinderOutsideFrameIsClipped() {
        val framing = Phase1FramingEvaluator.evaluate(
            720, 1280, acquisition(homography(0.95, -140.0, 100.0)), spec,
        )
        assertEquals(Phase1FramingStatus.MOVE_BACK, framing.status)
        assertTrue(framing.clipped)
    }

    @Test
    fun tinyCarrierReportsMoveCloser() {
        val framing = Phase1FramingEvaluator.evaluate(
            720, 1280, acquisition(homography(0.05, 337.5, 617.5)), spec,
        )
        assertEquals(Phase1FramingStatus.TOO_SMALL, framing.status)
        assertFalse(framing.clipped)
    }

    @Test
    fun trackingStateDerivesFromV7Source() {
        assertEquals(Phase1TrackingState.TRACKING, Phase1TrackingState.fromSource("V7_SYNC_TRACKED"))
        assertEquals(Phase1TrackingState.HOLDING, Phase1TrackingState.fromSource("V7_TRACK_HOLD"))
        assertEquals(Phase1TrackingState.ACQUIRED, Phase1TrackingState.fromSource("V7_FINDERS_ACQUIRED"))
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
