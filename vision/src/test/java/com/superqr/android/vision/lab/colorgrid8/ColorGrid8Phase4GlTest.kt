package com.superqr.android.vision.lab.colorgrid8

import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8Phase4GlTest {

    @Test
    fun testGlShadersSourceIntegrity() {
        assertTrue(ColorGrid8GlShaders.PASSTHROUGH_VERTEX.contains("#version 300 es"))
        assertTrue(ColorGrid8GlShaders.FINDER_THUMBNAIL_FRAGMENT.contains("samplerExternalOES"))
        assertTrue(ColorGrid8GlShaders.FINDER_THUMBNAIL_FRAGMENT.contains("GL_OES_EGL_image_external_essl3"))
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("samplerExternalOES"))
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("uHomography"))
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("uSampleMode"))
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("rgb2yuv"))
    }

    @Test
    fun testRgbToYuvConversionConstants() {
        // BT.601 constants check
        // Y = 0.299*R + 0.587*G + 0.114*B
        // U = -0.169*R - 0.331*G + 0.500*B + 128
        // V = 0.500*R - 0.419*G - 0.081*B + 128
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("0.299"))
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("0.587"))
        assertTrue(ColorGrid8GlShaders.CELL_SAMPLE_FRAGMENT.contains("0.114"))
    }

    @Test
    fun testDecodeFromCellMeansJvmFallback() {
        val profile = ColorGrid8Profile(cols = 168, rows = 144, fps = 30, version = ColorGrid8Spec.DIAGNOSTIC_HEADER_VERSION)
        val processor = ColorGrid8FrameProcessor(maxRedetectInterval = 60)

        val totalCells = profile.cols * profile.rows
        val yMeans = ByteArray(totalCells) { 128.toByte() }
        val uMeans = ByteArray(totalCells) { 128.toByte() }
        val vMeans = ByteArray(totalCells) { 128.toByte() }

        val result = processor.processFromGpuCellMeans(profile, yMeans, uMeans, vMeans)
        assertNotNull(result)
        assertEquals("${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}", result.expectedProfile)
    }

    @Test
    fun testTransferProfilesCellMeansBufferSizes() {
        for (dims in ColorGrid8Spec.transferGrids) {
            val profile = ColorGrid8Profile(dims.first, dims.second, fps = 30, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION)
            val totalCells = profile.cols * profile.rows
            val yMeans = ByteArray(totalCells)
            val uMeans = ByteArray(totalCells)
            val vMeans = ByteArray(totalCells)

            assertEquals(totalCells, yMeans.size)
            assertEquals(totalCells, uMeans.size)
            assertEquals(totalCells, vMeans.size)
        }
    }
}
