package com.superqr.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateNormalizationTest {
    @Test
    fun normalizedDimensionsFollowRotation() {
        assertEquals(100 to 50, FrameRotationHelper.getNormalizedDimensions(100, 50, 0))
        assertEquals(50 to 100, FrameRotationHelper.getNormalizedDimensions(100, 50, 90))
        assertEquals(100 to 50, FrameRotationHelper.getNormalizedDimensions(100, 50, 180))
        assertEquals(50 to 100, FrameRotationHelper.getNormalizedDimensions(100, 50, 270))
    }

    @Test
    fun rawAndNormalizedCoordinatesRoundTrip() {
        val rawWidth = 100
        val rawHeight = 50
        val points = listOf(0 to 0, 99 to 0, 0 to 49, 99 to 49, 15 to 37)
        for (rotation in listOf(0, 90, 180, 270)) {
            val (normalizedWidth, normalizedHeight) =
                FrameRotationHelper.getNormalizedDimensions(rawWidth, rawHeight, rotation)
            for ((rawX, rawY) in points) {
                val (x, y) = FrameRotationHelper.mapRawToNormalized(
                    rawX, rawY, rawWidth, rawHeight, rotation,
                )
                assert(x in 0 until normalizedWidth)
                assert(y in 0 until normalizedHeight)
                val (roundTripX, roundTripY) = FrameRotationHelper.mapNormalizedToRaw(
                    x, y, rawWidth, rawHeight, rotation,
                )
                assertEquals(rawX, roundTripX)
                assertEquals(rawY, roundTripY)
            }
        }
    }
}
