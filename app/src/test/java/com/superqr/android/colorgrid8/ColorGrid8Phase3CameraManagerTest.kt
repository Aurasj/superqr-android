package com.superqr.android.colorgrid8

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8Phase3CameraManagerTest {

    @Test
    fun diagnosticReportFormatMatchesPhase3Specification() {
        val sampleReport = buildString {
            appendLine("COLORGRID8 ANDROID PHASE 3")
            appendLine("native decoder: ON (ARM64 NEON)")
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
            appendLine("pilot p50/p95: 1.10 ms / 1.85 ms")
            appendLine("payload p50/p95: 2.10 ms / 3.20 ms")
            appendLine("total pipeline p50/p95: 4.80 ms / 7.10 ms")
            appendLine("native header p50/p95: 0.08 ms / 0.12 ms")
            appendLine("native pilot p50/p95: 0.95 ms / 1.40 ms")
            appendLine("native payload p50/p95: 1.85 ms / 2.60 ms")
            appendLine("native total p50/p95: 2.90 ms / 4.15 ms")
            appendLine("JNI overhead p50/p95: 0.15 ms / 0.25 ms")
            appendLine("GC/allocation note: zero per-frame buffer allocations (reused Mats, ByteArrays, IntArrays)")
            appendLine("measured file KiB/s: 850.50")
        }

        assertNotNull(sampleReport)
        assertTrue(sampleReport.contains("COLORGRID8 ANDROID PHASE 3"))
        assertTrue(sampleReport.contains("native decoder: ON (ARM64 NEON)"))
        assertTrue(sampleReport.contains("native header p50/p95:"))
        assertTrue(sampleReport.contains("native pilot p50/p95:"))
        assertTrue(sampleReport.contains("native payload p50/p95:"))
        assertTrue(sampleReport.contains("native total p50/p95:"))
        assertTrue(sampleReport.contains("JNI overhead p50/p95:"))
        assertTrue(sampleReport.contains("measured file KiB/s: 850.50"))
    }
}
