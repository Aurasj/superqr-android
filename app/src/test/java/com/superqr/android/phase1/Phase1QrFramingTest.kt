package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1QrFramingTest {
    @Test
    fun undecodedQrUsesFullAnalysisFrame() {
        val geometry = Phase1FramingEvaluator.evaluateQr(
            720, 1280, V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED"),
        )
        assertEquals(Phase1FramingStatus.QR_SEARCHING, geometry.status)
        assertEquals(720, geometry.frameWidth)
        assertEquals(1280, geometry.frameHeight)
    }

    @Test
    fun detectedQuadReportsDetectedInsteadOfSearching() {
        val quad = listOf(
            doubleArrayOf(100.0, 100.0), doubleArrayOf(600.0, 100.0),
            doubleArrayOf(600.0, 600.0), doubleArrayOf(100.0, 600.0),
        )
        val geometry = Phase1FramingEvaluator.evaluateQr(
            720, 1280,
            V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED", quad = quad),
        )
        assertEquals(Phase1FramingStatus.QR_DETECTED, geometry.status)
        assertEquals("QR_NATIVE_DETECTED", geometry.source)
    }

    @Test
    fun validatedQrReportsGood() {
        val envelope = V7LabRunEnvelope(V7LabRunState.READY, 5, 0x98D9, 0, 256, 3)
        val geometry = Phase1FramingEvaluator.evaluateQr(
            720, 1280, V7Phase1QrResult(true, true, 0, 1465, envelope = envelope),
        )
        assertEquals(Phase1FramingStatus.QR_GOOD, geometry.status)
    }

    @Test
    fun clippedQrRequestsMoveBack() {
        val quad = listOf(
            doubleArrayOf(-20.0, 100.0), doubleArrayOf(400.0, 100.0),
            doubleArrayOf(400.0, 500.0), doubleArrayOf(-20.0, 500.0),
        )
        val geometry = Phase1FramingEvaluator.evaluateQr(
            720, 1280,
            V7Phase1QrResult(true, false, null, 0, failure = "QR_NOT_DECODED", quad = quad),
        )
        assertTrue(geometry.clipped)
        assertEquals(Phase1FramingStatus.MOVE_BACK, geometry.status)
    }
}
