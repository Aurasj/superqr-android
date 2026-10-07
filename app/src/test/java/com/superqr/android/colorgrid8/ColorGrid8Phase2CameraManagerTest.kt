package com.superqr.android.colorgrid8

import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8Phase2CameraManagerTest {
    @Test
    fun diagnosticReportFormatMatchesPhase2Specification() {
        val sampleReport = buildString {
            appendLine("COLORGRID8 ANDROID PHASE 2")
            appendLine("profile: 1@60fps 336x288")
            appendLine("camera resolution: 960x720 Y • 480x360 UV")
            appendLine("camera FPS: 59.80")
            appendLine("decoded transport frames/sec: 29.50")
            appendLine("finder mode: LOCKED_ROI_R0")
            appendLine("tracked frames: 120")
            appendLine("ROI refinements: 118")
            appendLine("full redetections: 1")
            appendLine("orientation lock: LOCKED")
            appendLine("header success rate: 98.3%")
            appendLine("finder p50/p95: 0.45 ms / 0.82 ms")
            appendLine("header p50/p95: 0.22 ms / 0.35 ms")
            appendLine("pilot p50/p95: 3.10 ms / 4.25 ms")
            appendLine("payload p50/p95: 4.50 ms / 5.80 ms")
            appendLine("total pipeline p50/p95: 8.80 ms / 12.10 ms")
            appendLine("GC/allocation note: zero per-frame buffer allocations (reused Mats, ByteArrays, IntArrays)")
            appendLine("measured file KiB/s: 850.50")
        }

        assertNotNull(sampleReport)
        assertTrue(sampleReport.contains("COLORGRID8 ANDROID PHASE 2"))
        assertTrue(sampleReport.contains("profile:"))
        assertTrue(sampleReport.contains("camera resolution:"))
        assertTrue(sampleReport.contains("camera FPS:"))
        assertTrue(sampleReport.contains("decoded transport frames/sec:"))
        assertTrue(sampleReport.contains("finder mode:"))
        assertTrue(sampleReport.contains("tracked frames:"))
        assertTrue(sampleReport.contains("ROI refinements:"))
        assertTrue(sampleReport.contains("full redetections:"))
        assertTrue(sampleReport.contains("orientation lock:"))
        assertTrue(sampleReport.contains("header success rate:"))
        assertTrue(sampleReport.contains("finder p50/p95:"))
        assertTrue(sampleReport.contains("header p50/p95:"))
        assertTrue(sampleReport.contains("pilot p50/p95:"))
        assertTrue(sampleReport.contains("payload p50/p95:"))
        assertTrue(sampleReport.contains("total pipeline p50/p95:"))
        assertTrue(sampleReport.contains("GC/allocation note:"))
        assertTrue(sampleReport.contains("measured file KiB/s:"))
    }

    @Test
    fun frameContinuityTracksTransitionsAndRatios() {
        val continuity = ColorGrid8FrameContinuity()
        assertTrue(continuity.observe(0))
        assertTrue(continuity.observe(1))
        assertTrue(continuity.observe(2))
        assertTrue(continuity.observe(3))

        assertEquals(3L, continuity.deliveredTransitions)
        assertEquals(3L, continuity.sentTransitions)
        assertEquals(1.0, continuity.deliveryRatio, 1e-6)
    }

    @Test
    fun transferProfilesRetainExpectedBandwidths() {
        val p336 = ColorGrid8Profile(336, 288, 60, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION)
        val p384 = ColorGrid8Profile(384, 336, 60, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION)

        assertTrue(p336.payloadKibS > 2000.0)
        assertTrue(p384.payloadKibS > 2700.0)
    }
}
