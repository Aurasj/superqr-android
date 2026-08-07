package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.normalization.FrameRotationHelper
import org.junit.Assert.*
import org.junit.Test

class V6ReplaySamplerTest {

    @Test
    fun testFrameRotationHelperDimensions() {
        assertEquals(Pair(100, 50), FrameRotationHelper.getNormalizedDimensions(100, 50, 0))
        assertEquals(Pair(50, 100), FrameRotationHelper.getNormalizedDimensions(100, 50, 90))
        assertEquals(Pair(100, 50), FrameRotationHelper.getNormalizedDimensions(100, 50, 180))
        assertEquals(Pair(50, 100), FrameRotationHelper.getNormalizedDimensions(100, 50, 270))
    }

    @Test
    fun testRawToNormalizedAndInverseRotation0() {
        val rawWidth = 100
        val rawHeight = 50
        val rot = 0

        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(10, 20, rawWidth, rawHeight, rot)
        assertEquals(10, nx)
        assertEquals(20, ny)

        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rot)
        assertEquals(10, rx)
        assertEquals(20, ry)
    }

    @Test
    fun testRawToNormalizedAndInverseRotation90() {
        val rawWidth = 100
        val rawHeight = 50
        val rot = 90

        // Corner TL (0, 0) raw -> (rawHeight - 1 - 0, 0) = (49, 0) normalized
        val (nxTL, nyTL) = FrameRotationHelper.mapRawToNormalized(0, 0, rawWidth, rawHeight, rot)
        assertEquals(49, nxTL)
        assertEquals(0, nyTL)

        val (rxTL, ryTL) = FrameRotationHelper.mapNormalizedToRaw(nxTL, nyTL, rawWidth, rawHeight, rot)
        assertEquals(0, rxTL)
        assertEquals(0, ryTL)
    }

    @Test
    fun testRawToNormalizedAndInverseRotation180() {
        val rawWidth = 100
        val rawHeight = 50
        val rot = 180

        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(10, 20, rawWidth, rawHeight, rot)
        assertEquals(100 - 1 - 10, nx)
        assertEquals(50 - 1 - 20, ny)

        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rot)
        assertEquals(10, rx)
        assertEquals(20, ry)
    }

    @Test
    fun testRawToNormalizedAndInverseRotation270() {
        val rawWidth = 100
        val rawHeight = 50
        val rot = 270

        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(10, 20, rawWidth, rawHeight, rot)
        assertEquals(20, nx)
        assertEquals(100 - 1 - 10, ny)

        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawWidth, rawHeight, rot)
        assertEquals(10, rx)
        assertEquals(20, ry)
    }

    @Test
    fun testLumaPackerWithStridesAndCrop() {
        // Image 10x10, crop 4x4 from (2, 2) to (6, 6)
        val imageWidth = 10
        val imageHeight = 10
        val yRowStride = 12
        val yPixelStride = 1
        val yBytes = ByteArray(yRowStride * imageHeight)

        // Set pixel at crop (1, 1) relative to crop top-left (so absolute raw image (3, 3)) = 200
        yBytes[3 * yRowStride + 3 * yPixelStride] = 200.toByte()

        val frame = V6RawYuvFrame(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropLeft = 2,
            cropTop = 2,
            cropRight = 6,
            cropBottom = 6,
            rotationDegrees = 0,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uRowStride = 10,
            uPixelStride = 1,
            vRowStride = 10,
            vPixelStride = 1,
            yPlaneBytes = yBytes,
            uPlaneBytes = ByteArray(100),
            vPlaneBytes = ByteArray(100)
        )

        val packed = V6ReplayLumaPacker.pack(frame)
        assertEquals(4, packed.width)
        assertEquals(4, packed.height)

        // Pixel at normalized (1, 1) should be 200 (since rotation = 0)
        val valAt11 = packed.bytes[1 * 4 + 1].toInt() and 0xFF
        assertEquals(200, valAt11)
    }

    @Test
    fun testChromaSamplerWithStridesAndCrop() {
        val uRowStride = 16
        val uPixelStride = 2
        val uBytes = ByteArray(uRowStride * 10)
        val vBytes = ByteArray(uRowStride * 10)

        // Crop offset left = 4, top = 4
        // Normalized (0, 0) maps to raw (0, 0) within crop -> absolute raw (4, 4)
        // Chroma coordinate = (4 + 0) / 2 = 2, (4 + 0) / 2 = 2
        val uIdx = 2 * uRowStride + 2 * uPixelStride
        uBytes[uIdx] = 150.toByte()
        vBytes[uIdx] = 220.toByte()

        val frame = V6RawYuvFrame(
            imageWidth = 10,
            imageHeight = 10,
            cropLeft = 4,
            cropTop = 4,
            cropRight = 8,
            cropBottom = 8,
            rotationDegrees = 0,
            yRowStride = 10,
            yPixelStride = 1,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vRowStride = uRowStride,
            vPixelStride = uPixelStride,
            yPlaneBytes = ByteArray(100),
            uPlaneBytes = uBytes,
            vPlaneBytes = vBytes
        )

        val sampler = V6ReplayChromaSampler(frame)
        val dest = IntArray(2)
        val readSuccess = sampler.read(0.0, 0.0, dest)

        assertTrue(readSuccess)
        assertEquals(150, dest[0])
        assertEquals(220, dest[1])
    }
}
