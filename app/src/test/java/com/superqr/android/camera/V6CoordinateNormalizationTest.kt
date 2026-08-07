package com.superqr.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class V6CoordinateNormalizationTest {

    @Test
    fun testNormalizedDimensions() {
        // Raw dimensions: W=100, H=50 (asymmetric)
        val (w0, h0) = FrameRotationHelper.getNormalizedDimensions(100, 50, 0)
        assertEquals(100, w0)
        assertEquals(50, h0)

        val (w90, h90) = FrameRotationHelper.getNormalizedDimensions(100, 50, 90)
        assertEquals(50, w90)
        assertEquals(100, h90)

        val (w180, h180) = FrameRotationHelper.getNormalizedDimensions(100, 50, 180)
        assertEquals(100, w180)
        assertEquals(50, h180)

        val (w270, h270) = FrameRotationHelper.getNormalizedDimensions(100, 50, 270)
        assertEquals(50, w270)
        assertEquals(100, h270)
    }

    @Test
    fun testRawToNormalizedMapping0Degrees() {
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(100, 50, 0)
        assertEquals(100, nw)
        assertEquals(50, nh)

        // (10, 20) -> (10, 20)
        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(10, 20, 100, 50, 0)
        assertEquals(10, nx)
        assertEquals(20, ny)
    }

    @Test
    fun testRawToNormalizedMapping90Degrees() {
        // Raw dimensions 100 x 50 (W=100, H=50). Normalized 50 x 100 (NW=50, NH=100).
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(100, 50, 90)
        assertEquals(50, nw)
        assertEquals(100, nh)

        // Top-Left raw (0, 0) -> nx = H - 1 - 0 = 49, ny = 0 -> (49, 0) (Top-Right normalized)
        val (nxTL, nyTL) = FrameRotationHelper.mapRawToNormalized(0, 0, 100, 50, 90)
        assertEquals(49, nxTL)
        assertEquals(0, nyTL)

        // Bottom-Left raw (0, 49) -> nx = 50 - 1 - 49 = 0, ny = 0 -> (0, 0) (Top-Left normalized)
        val (nxBL, nyBL) = FrameRotationHelper.mapRawToNormalized(0, 49, 100, 50, 90)
        assertEquals(0, nxBL)
        assertEquals(0, nyBL)

        // Top-Right raw (99, 0) -> nx = 49, ny = 99 -> (49, 99) (Bottom-Right normalized)
        val (nxTR, nyTR) = FrameRotationHelper.mapRawToNormalized(99, 0, 100, 50, 90)
        assertEquals(49, nxTR)
        assertEquals(99, nyTR)
    }

    @Test
    fun testNormalizedToRawMapping90Degrees() {
        // Raw dimensions 100 x 50. Normalized 50 x 100.
        // Map normalized (0, 0) back to raw.
        // nx = 0, ny = 0.
        // rx = ny = 0, ry = H - 1 - nx = 50 - 1 - 0 = 49 -> (0, 49) (Bottom-Left raw)
        val (rxTL, ryTL) = FrameRotationHelper.mapNormalizedToRaw(0, 0, 100, 50, 90)
        assertEquals(0, rxTL)
        assertEquals(49, ryTL)
    }

    @Test
    fun testRoundTripConsistencyForAllRotations() {
        val rawWidth = 100
        val rawHeight = 50
        val rotations = listOf(0, 90, 180, 270)

        // Test asymmetric points
        val testPoints = listOf(
            Pair(0, 0),
            Pair(99, 0),
            Pair(0, 49),
            Pair(99, 49),
            Pair(15, 37)
        )

        for (rotation in rotations) {
            val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(rawWidth, rawHeight, rotation)
            for ((rx, ry) in testPoints) {
                val (nx, ny) = FrameRotationHelper.mapRawToNormalized(rx, ry, rawWidth, rawHeight, rotation)
                
                // Assert mapped coordinates stay strictly inside normalized bounds
                assert(nx in 0 until nw) { "nx=$nx out of bounds for rotation $rotation" }
                assert(ny in 0 until nh) { "ny=$ny out of bounds for rotation $rotation" }

                val (rxBack, ryBack) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rotation)
                assertEquals("Round trip rx failed for rotation $rotation", rx, rxBack)
                assertEquals("Round trip ry failed for rotation $rotation", ry, ryBack)
            }
        }
    }
}
