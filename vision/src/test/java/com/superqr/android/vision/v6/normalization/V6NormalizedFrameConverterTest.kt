package com.superqr.android.vision.v6.normalization

import com.superqr.android.vision.v6.replay.V6RawYuvFrame
import com.superqr.android.vision.v6.replay.V6ReplayLumaPacker
import org.junit.Assert.*
import org.junit.Test

class V6NormalizedFrameConverterTest {

    private fun createTestFrame(
        imageWidth: Int = 640,
        imageHeight: Int = 480,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = imageWidth,
        cropBottom: Int = imageHeight,
        rotationDegrees: Int = 90,
        yRowStride: Int = imageWidth,
        yPixelStride: Int = 1,
        uRowStride: Int = imageWidth / 2,
        uPixelStride: Int = 1,
        vRowStride: Int = imageWidth / 2,
        vPixelStride: Int = 1,
        yFill: Byte = 128.toByte(),
        uFill: Byte = 128.toByte(),
        vFill: Byte = 128.toByte()
    ): V6RawYuvFrame {
        val ySize = yRowStride * imageHeight
        val uSize = uRowStride * (imageHeight / 2)
        val vSize = vRowStride * (imageHeight / 2)

        return V6RawYuvFrame(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
            rotationDegrees = rotationDegrees,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vRowStride = vRowStride,
            vPixelStride = vPixelStride,
            yPlaneBytes = ByteArray(ySize) { yFill },
            uPlaneBytes = ByteArray(uSize) { uFill },
            vPlaneBytes = ByteArray(vSize) { vFill }
        )
    }

    @Test
    fun testRaw640x480Rotation90ToNormalized480x640() {
        val frame = createTestFrame(imageWidth = 640, imageHeight = 480, rotationDegrees = 90)
        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)

        assertEquals(480, result.width)
        assertEquals(640, result.height)
        assertEquals(480 * 640 * 3, result.bgrBytes.size)
    }

    @Test
    fun testRotation0() {
        val frame = createTestFrame(imageWidth = 100, imageHeight = 50, rotationDegrees = 0)
        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)

        assertEquals(100, result.width)
        assertEquals(50, result.height)
    }

    @Test
    fun testRotation180() {
        val frame = createTestFrame(imageWidth = 100, imageHeight = 50, rotationDegrees = 180)
        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)

        assertEquals(100, result.width)
        assertEquals(50, result.height)
    }

    @Test
    fun testRotation270() {
        val frame = createTestFrame(imageWidth = 100, imageHeight = 50, rotationDegrees = 270)
        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)

        assertEquals(50, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun testCropRectOffsetsRespected() {
        // Raw 100x100, crop 40x20 at left=10, top=5, right=50, bottom=25
        val frame = createTestFrame(
            imageWidth = 100,
            imageHeight = 100,
            cropLeft = 10,
            cropTop = 5,
            cropRight = 50,
            cropBottom = 25,
            rotationDegrees = 0
        )
        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)

        assertEquals(40, result.width)
        assertEquals(20, result.height)
        assertEquals(40 * 20 * 3, result.bgrBytes.size)
    }

    @Test
    fun testRowStrideAndPixelStrideRespected() {
        val imageWidth = 20
        val imageHeight = 20
        val yRowStride = 40 // Larger row stride
        val yPixelStride = 2 // Interleaved pixel stride
        val yBytes = ByteArray(yRowStride * imageHeight)

        // Set pixel at raw (4, 4) -> index = 4 * 40 + 4 * 2 = 168
        yBytes[4 * yRowStride + 4 * yPixelStride] = 240.toByte()

        val frame = V6RawYuvFrame(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropLeft = 0,
            cropTop = 0,
            cropRight = imageWidth,
            cropBottom = imageHeight,
            rotationDegrees = 0,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uRowStride = 10,
            uPixelStride = 1,
            vRowStride = 10,
            vPixelStride = 1,
            yPlaneBytes = yBytes,
            uPlaneBytes = ByteArray(100) { 128.toByte() },
            vPlaneBytes = ByteArray(100) { 128.toByte() }
        )

        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)
        val destIdx = (4 * 20 + 4) * 3
        val blueVal = result.bgrBytes[destIdx].toInt() and 0xFF

        // Luma 240 with neutral chroma yields bright grayscale (approx 240)
        assertTrue("Expected bright value from custom stride pixel, got $blueVal", blueVal > 200)
    }

    @Test
    fun testYuvSamplingUsesSamePhysicalPixel() {
        val imageWidth = 10
        val imageHeight = 10
        val yBytes = ByteArray(100) { 128.toByte() }
        val uBytes = ByteArray(25) { 128.toByte() }
        val vBytes = ByteArray(25) { 128.toByte() }

        // Set pure red at raw (2, 2)
        // Y = 76, U = 85, V = 255 -> converts to BGR (0, 0, ~255)
        val rawX = 2
        val rawY = 2
        yBytes[rawY * 10 + rawX] = 76.toByte()
        uBytes[(rawY / 2) * 5 + (rawX / 2)] = 85.toByte()
        vBytes[(rawY / 2) * 5 + (rawX / 2)] = 255.toByte()

        val frame = V6RawYuvFrame(
            imageWidth = 10,
            imageHeight = 10,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 10,
            cropBottom = 10,
            rotationDegrees = 0,
            yRowStride = 10,
            yPixelStride = 1,
            uRowStride = 5,
            uPixelStride = 1,
            vRowStride = 5,
            vPixelStride = 1,
            yPlaneBytes = yBytes,
            uPlaneBytes = uBytes,
            vPlaneBytes = vBytes
        )

        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)
        val destIdx = (2 * 10 + 2) * 3
        val b = result.bgrBytes[destIdx].toInt() and 0xFF
        val r = result.bgrBytes[destIdx + 2].toInt() and 0xFF

        assertTrue("Red channel should be high (got $r)", r > 200)
        assertTrue("Blue channel should be low (got $b)", b < 50)
    }

    @Test
    fun testDimensionsMatchNormalizedLuma() {
        val frame = createTestFrame(imageWidth = 640, imageHeight = 480, rotationDegrees = 90)
        val normRgb = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)
        val packedLuma = V6ReplayLumaPacker.pack(frame)

        assertEquals(packedLuma.width, normRgb.width)
        assertEquals(packedLuma.height, normRgb.height)
    }

    @Test
    fun testSyntheticColoredSquareLocationAfterRotation90() {
        // Raw 100x50, rotation 90 -> normalized 50x100
        val imageWidth = 100
        val imageHeight = 50
        val yBytes = ByteArray(imageWidth * imageHeight) { 0.toByte() }

        // Draw white square at raw (10..19, 10..19)
        for (ry in 10..19) {
            for (rx in 10..19) {
                yBytes[ry * imageWidth + rx] = 255.toByte()
            }
        }

        val frame = V6RawYuvFrame(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropLeft = 0,
            cropTop = 0,
            cropRight = imageWidth,
            cropBottom = imageHeight,
            rotationDegrees = 90,
            yRowStride = imageWidth,
            yPixelStride = 1,
            uRowStride = imageWidth / 2,
            uPixelStride = 1,
            vRowStride = imageWidth / 2,
            vPixelStride = 1,
            yPlaneBytes = yBytes,
            uPlaneBytes = ByteArray(imageWidth * imageHeight / 4) { 128.toByte() },
            vPlaneBytes = ByteArray(imageWidth * imageHeight / 4) { 128.toByte() }
        )

        val result = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(frame)
        assertEquals(50, result.width)
        assertEquals(100, result.height)

        // Raw (10, 10) for 90° rotation maps to normalized nx = rawHeight - 1 - ry = 50 - 1 - 10 = 39, ny = rx = 10
        val destIdx = (10 * 50 + 39) * 3
        val blueVal = result.bgrBytes[destIdx].toInt() and 0xFF
        assertTrue("Rotated square pixel should be bright white (got $blueVal)", blueVal > 200)
    }
}
