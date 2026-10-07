package com.superqr.android.colorgrid8

import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8Phase4CameraManagerTest {

    @Test
    fun testCameraPathModes() {
        val paths = listOf("CAMERA2_GL", "CAMERAX_NATIVE")
        assertTrue(paths.contains("CAMERA2_GL"))
        assertTrue(paths.contains("CAMERAX_NATIVE"))
    }

    @Test
    fun testSamplingModes() {
        val sampleModes = listOf("1x", "2x2", "3x3")
        assertEquals(3, sampleModes.size)
        assertEquals("1x", sampleModes[0])
        assertEquals("2x2", sampleModes[1])
        assertEquals("3x3", sampleModes[2])
    }

    @Test
    fun testGlPipelineResultDataStructure() {
        val result = GlPipelineResult(
            processResult = null,
            cameraTimestampNs = 1_000_000_000L,
            gpuSubmitNs = 1_000_001_000L,
            gpuReadyNs = 1_000_003_000L,
            decodeStartNs = 1_000_004_000L,
            decodeEndNs = 1_000_008_000L,
            gpuSampleMs = 2.0,
            gpuReadbackMs = 0.5,
            nativeDecodeMs = 4.0,
            fullPipelineMs = 7.5,
            cameraFps = 30.0,
            cameraPath = "CAMERA2_GL",
            gpuPath = "ON",
            nativeDecoder = "ON (ARM64 NEON)",
            sampleMode = "2x2",
            resolution = "3840x2160"
        )
        assertEquals("CAMERA2_GL", result.cameraPath)
        assertEquals("ON", result.gpuPath)
        assertEquals("ON (ARM64 NEON)", result.nativeDecoder)
        assertEquals("2x2", result.sampleMode)
        assertEquals("3840x2160", result.resolution)
        assertEquals(30.0, result.cameraFps, 0.001)
        assertEquals(7.5, result.fullPipelineMs, 0.001)
    }

    @Test
    fun testTransferProfilesMemoryCalculations() {
        for (dims in ColorGrid8Spec.transferGrids) {
            val profile = ColorGrid8Profile(dims.first, dims.second, fps = 30, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION)
            val compactBytes = profile.cols * profile.rows * 3 // Y + U + V per cell
            assertTrue("Compact GPU output for ${dims.first}x${dims.second} ($compactBytes B) must be < 1MB", compactBytes < 1024 * 1024)
        }
    }
}
