package com.superqr.android.ui.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import com.superqr.android.vision.v7_capacity_lab.V7Phase1QrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1QrFramingTest {
    @Test
    fun undecodedQrSearchStillDescribesTheExactFullAnalysisFrame() {
        val geometry = Phase1FramingEvaluator.evaluateQr(
            720, 1280,
            V7Phase1QrResult(false, false, null, 0, failure = "QR_NOT_DECODED"),
        )
        assertEquals(Phase1FramingMode.QR, geometry.mode)
        assertEquals(Phase1FramingStatus.QR_SEARCHING, geometry.status)
        assertEquals(720, geometry.frameWidth)
        assertEquals(1280, geometry.frameHeight)
        assertTrue(geometry.safeInsetPx > 0f)
        assertTrue(geometry.detail.contains("full frame", ignoreCase = true))
    }

    @Test
    fun validatedQrReadyFrameReportsQrFramingGood() {
        val envelope = V7LabRunEnvelope(V7LabRunState.READY, 5, 0x98D9, 0, 256, 3)
        val geometry = Phase1FramingEvaluator.evaluateQr(
            720, 1280,
            V7Phase1QrResult(true, true, 0, 1465, envelope = envelope),
        )
        assertEquals(Phase1FramingMode.QR, geometry.mode)
        assertEquals(Phase1FramingStatus.QR_GOOD, geometry.status)
        assertTrue(geometry.detail.contains("exact full ImageAnalysis frame"))
    }
}
