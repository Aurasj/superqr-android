package com.superqr.android.vision.v6

import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v6.tracking.TrackingState
import com.superqr.android.vision.v6.tracking.V6TemporalTracker
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.Core

class V6TemporalTrackerTest {

    init {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        } catch (e: Throwable) {
            println("OpenCV native library load warning: ${e.message}")
        }
    }

    private fun createDummyResult(valid: Boolean): V6StaticResult {
        return V6StaticResult(
            borderFound = valid,
            detectedQuad = if (valid) listOf(doubleArrayOf(60.0, 60.0), doubleArrayOf(940.0, 60.0), doubleArrayOf(940.0, 940.0), doubleArrayOf(60.0, 940.0)) else null,
            contourArea = if (valid) 774400.0 else 0.0,
            decodedCornerIds = if (valid) mapOf("TL" to "TL", "TR" to "TR", "BR" to "BR", "BL" to "BL") else emptyMap(),
            decodedCornerBits = if (valid) mapOf("TL" to "1000", "TR" to "0100", "BR" to "0010", "BL" to "0001") else emptyMap(),
            decodedCornerDistances = if (valid) mapOf("TL" to 0, "TR" to 0, "BR" to 0, "BL" to 0) else emptyMap(),
            decodedCornerMargins = if (valid) mapOf("TL" to 2, "TR" to 2, "BR" to 2, "BL" to 2) else emptyMap(),
            bitSamples = emptyMap(),
            cornerMatches = emptyMap(),
            orientationResolved = valid,
            reprojectionError = if (valid) 2.0 else Double.NaN,
            pilotYUVs = emptyMap(),
            cellAccuracy = if (valid) 100.0 else 0.0,
            uncertainCells = 0,
            decodedCrc32 = null,
            expectedCrc32 = null,
            colorCorrect = if (valid) 400 else 0,
            colorUncertain = 0,
            colorTotal = if (valid) 400 else 0,
            confusionMatrix = null,
            processingTimeMs = 5,
            failureReason = if (valid) null else "Border lost",
            debugImagePath = null,
            trackingState = if (valid) "LOCKED" else "SEARCHING",
            finalInvHomography = if (valid) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) else null
        )
    }

    @Test
    fun testInitialStateIsSearching() {
        val tracker = V6TemporalTracker()
        assertEquals(TrackingState.SEARCHING, tracker.state)
        assertEquals(0, tracker.consecutiveAcquisitionCount)
        assertEquals(0, tracker.missedFrameCount)
    }

    @Test
    fun testAcquisitionStreakToLocked() {
        val tracker = V6TemporalTracker()
        val validResult = createDummyResult(true)
        val hInv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val r1 = tracker.processFrame(null, validResult, hInv)
        assertEquals("ACQUIRING", r1.trackingState)
        assertEquals(1, tracker.consecutiveAcquisitionCount)

        val r2 = tracker.processFrame(null, validResult, hInv)
        assertEquals("ACQUIRING", r2.trackingState)
        assertEquals(2, tracker.consecutiveAcquisitionCount)

        val r3 = tracker.processFrame(null, validResult, hInv)
        assertEquals("LOCKED", r3.trackingState)
        assertEquals(3, tracker.consecutiveAcquisitionCount)

        val r4 = tracker.processFrame(null, validResult, hInv)
        assertEquals("TRACKING", r4.trackingState)
    }

    @Test
    fun testMissedFrameTolerance() {
        val tracker = V6TemporalTracker()
        val validResult = createDummyResult(true)
        val invalidResult = createDummyResult(false)
        val hInv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        repeat(3) { tracker.processFrame(null, validResult, hInv) }
        assertEquals(TrackingState.LOCKED, tracker.state)

        val rMiss1 = tracker.processFrame(null, invalidResult, null)
        assertEquals("REACQUIRING", rMiss1.trackingState)
        assertEquals(1, tracker.missedFrameCount)

        val rRecover = tracker.processFrame(null, validResult, hInv)
        assertEquals("TRACKING", rRecover.trackingState)
        assertEquals(0, tracker.missedFrameCount)
    }
}
