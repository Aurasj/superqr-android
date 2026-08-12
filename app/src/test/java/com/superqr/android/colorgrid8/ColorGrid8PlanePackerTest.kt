package com.superqr.android.colorgrid8

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8PlanePackerTest {
    @Test
    fun honorsNonZeroBufferBasePosition() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 98, 1, 2, 3, 4, 5, 6))
        source.position(2)
        val output = ByteArray(6)
        assertTrue(
            ColorGrid8PlanePacker.pack(
                source = source,
                cropLeft = 0,
                cropTop = 0,
                rawWidth = 3,
                rawHeight = 2,
                rowStride = 3,
                pixelStride = 1,
                rotationDegrees = 0,
                rowBuffer = ByteArray(3),
                destination = output,
            )
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), output)
    }

    @Test
    fun pixelStrideDoesNotRequireTrailingPaddingAfterLastSample() {
        // Plane begins at index 2. Two 2-sample rows, stride 4, pixel stride 2.
        // The final sample is the final byte of the buffer; no trailing padding exists.
        val source = ByteBuffer.wrap(byteArrayOf(91, 92, 1, 55, 2, 56, 3, 57, 4))
        source.position(2)
        val output = ByteArray(4)
        assertTrue(
            ColorGrid8PlanePacker.pack(
                source = source,
                cropLeft = 0,
                cropTop = 0,
                rawWidth = 2,
                rawHeight = 2,
                rowStride = 4,
                pixelStride = 2,
                rotationDegrees = 0,
                rowBuffer = ByteArray(3),
                destination = output,
            )
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output)
    }

    @Test
    fun honorsCropAndRotation() {
        val source = ByteBuffer.wrap(
            byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
            )
        )
        val output = ByteArray(4)
        assertTrue(
            ColorGrid8PlanePacker.pack(
                source = source,
                cropLeft = 1,
                cropTop = 1,
                rawWidth = 2,
                rawHeight = 2,
                rowStride = 4,
                pixelStride = 1,
                rotationDegrees = 90,
                rowBuffer = ByteArray(2),
                destination = output,
            )
        )
        // Crop is [[6,7],[10,11]], rotated 90 degrees clockwise.
        assertArrayEquals(byteArrayOf(10, 6, 11, 7), output)
    }
}
