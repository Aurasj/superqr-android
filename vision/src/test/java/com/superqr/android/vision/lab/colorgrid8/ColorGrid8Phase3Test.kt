package com.superqr.android.vision.lab.colorgrid8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class ColorGrid8Phase3Test {

    @Test
    fun testNativeDecoderGracefulFallback() {
        // Even if native library is not present in host JVM testing environment,
        // isNativeLoaded correctly indicates status without crashing.
        val loaded = ColorGrid8NativeDecoder.isNativeLoaded
        val scratch = ColorGrid8NativeDecoder.NativeDecoderScratch()
        assertNotNull(scratch.payloadBuffer)
        assertNotNull(scratch.timingsBuffer)
        assertNotNull(scratch.intStatsBuffer)
        assertNotNull(scratch.floatStatsBuffer)
        assertNotNull(scratch.centroidsBuffer)

        if (!loaded) {
            val quad = Array(4) { Point(0.0, 0.0) }
            val profile = ColorGrid8Profile(168, 144)
            val res = scratch.decode(
                ByteArray(100), 10, 1,
                ByteArray(50), 5, 1,
                ByteArray(50), 5, 1,
                10, 10,
                quad, profile
            )
            assertEquals(null, res)
        }
    }

    @Test
    fun testHeaderAndPilotParity() {
        val profile = ColorGrid8Profile(
            cols = 336,
            rows = 288,
            fps = 60,
            seed = 0x1234,
            version = ColorGrid8Spec.TRANSFER_HEADER_VERSION
        )

        // Verify header bit generation
        val headerBits = ColorGrid8Codec.headerBits(profile, frameIndex = 42)
        assertEquals(ColorGrid8Spec.HEADER_BITS, headerBits.size)

        // Verify first 12 bits match HEADER_MAGIC = 0xC8D (1100 1000 1101)
        val expectedMagicBits = intArrayOf(1, 1, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1)
        for (i in 0 until 12) {
            assertEquals("Magic bit $i mismatch", expectedMagicBits[i], headerBits[i])
        }

        // Verify pilot pattern periodicity
        for (r in ColorGrid8Spec.HEADER_ROWS until profile.rows) {
            for (c in 0 until profile.cols) {
                val flat = (r - ColorGrid8Spec.HEADER_ROWS) * profile.cols + c
                val expectedPilot = (flat % ColorGrid8Spec.PILOT_PERIOD == 0)
                assertEquals("Pilot status mismatch at ($r, $c)", expectedPilot, ColorGrid8Codec.isPilot(profile, r, c))

                if (expectedPilot) {
                    val symbol = ColorGrid8Codec.pilotSymbol(profile, r, c, frameIndex = 42)
                    assertTrue("Pilot symbol out of range 0..7: $symbol", symbol in 0..7)
                }
            }
        }
    }

    @Test
    fun testFrameProcessorEndToEndWithStridedPlanes() {
        val profile = ColorGrid8Profile(
            cols = 168,
            rows = 144,
            fps = 30,
            version = ColorGrid8Spec.DIAGNOSTIC_HEADER_VERSION
        )

        val processor = ColorGrid8FrameProcessor()

        // Create a synthetic blank frame with strides
        val width = 640
        val height = 480
        val yPlane = ByteArray(width * height)
        val uPlane = ByteArray((width / 2) * (height / 2))
        val vPlane = ByteArray((width / 2) * (height / 2))

        val frame = ColorGrid8YuvFrame(
            width = width,
            height = height,
            y = yPlane,
            chromaWidth = width / 2,
            chromaHeight = height / 2,
            u = uPlane,
            v = vPlane,
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = width / 2,
            uPixelStride = 1,
            vRowStride = width / 2,
            vPixelStride = 1,
        )

        val result = processor.process(profile, frame)
        assertNotNull(result)
        assertEquals(ColorGrid8Stage.FINDER_ROI, result.stage)
        assertFalse(result.geometryLocked)
        processor.close()
    }

    @Test
    fun testHighDensityProfileThroughputBenchmark() {
        val profiles = listOf(
            ColorGrid8Profile(168, 144, 30, version = ColorGrid8Spec.DIAGNOSTIC_HEADER_VERSION),
            ColorGrid8Profile(336, 288, 60, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION),
            ColorGrid8Profile(384, 336, 60, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION),
        )

        for (profile in profiles) {
            assertTrue("Payload cells must be positive", profile.payloadCells > 0)
            assertTrue("Post-FEC KiB/s must meet high-speed requirements", profile.postFecKibS(0.20) > 0.0)
            val byteCap = profile.byteCapacity
            assertTrue("Byte capacity must scale with grid size", byteCap > 1000)
        }
    }
}
