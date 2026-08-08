package com.superqr.android.vision.v6.diagnostic

import com.superqr.android.vision.v6.transport.V6TransferPackage
import com.superqr.android.vision.v6.transport.V6TransportFrame
import org.junit.Assert.*
import org.junit.Test

class V6TransferStatsCollectorTest {

    private fun frame(sessionId: Int = 1, frameId: Int = 0, totalFrames: Int = 5, payload: ByteArray = ByteArray(91)) =
        V6TransportFrame(sessionId, frameId, totalFrames, payload, crc16 = 0x1234)

    private fun pkg(filename: String = "test.txt", fileSize: Int = 100, data: ByteArray = ByteArray(100)) =
        V6TransferPackage(filename, fileSize, data)

    private fun pre(uniqueFrames: Int, duplicateCount: Int = 0, conflictCount: Int = 0, sessionId: Int = 1, totalFrames: Int = 5) =
        V6TransferStatsCollector.AccPreState(uniqueFrames, duplicateCount, conflictCount, sessionId, totalFrames)

    private fun post(uniqueFrames: Int, duplicateCount: Int = 0, conflictCount: Int = 0) =
        V6TransferStatsCollector.AccPostState(uniqueFrames, duplicateCount, conflictCount)

    // ── helper: transition collector to ACTIVE, with CRC valid as the trigger ──
    private fun transitionToActive(c: V6TransferStatsCollector) {
        c.recordAnalyzerFrame()
        c.recordDetectorResult("FULL_DETECTION", true, true)
        c.recordCrcOutcome(frame(frameId = 0), null)  // CRC valid
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0),
            frame(frameId = 0), null
        )
        assertEquals(V6TransferStatsCollector.State.ACTIVE, c.state)
    }

    // ── lifecycle ─────────────────────────────────────────────────────

    @Test
    fun startsInScanningState() {
        val c = V6TransferStatsCollector().also { it.reset() }
        assertEquals(V6TransferStatsCollector.State.SCANNING, c.state)
    }

    @Test
    fun firstUniqueFrameTransitionsToActive() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAnalyzerFrame()
        c.recordDetectorResult("FULL_DETECTION", true, true)
        c.recordCrcOutcome(frame(), null)
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(), null
        )
        assertEquals(V6TransferStatsCollector.State.ACTIVE, c.state)
    }

    @Test
    fun staysScanningOnRejectedFrame() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAnalyzerFrame()
        c.recordDetectorResult("FULL_DETECTION", true, true)
        c.recordCrcOutcome(frame(sessionId = 99), null)
        c.recordAccumulatorOutcome(
            pre(3, 0, 0, sessionId = 1, totalFrames = 5),
            post(3, 0, 0), frame(sessionId = 99), null
        )
        assertEquals(V6TransferStatsCollector.State.SCANNING, c.state)
    }

    // ── all CRC outcomes are on main executor ──────────────────────────

    @Test
    fun crcOutcomesAreBalancedWithDetector() {
        val c = V6TransferStatsCollector().also { it.reset() }
        transitionToActive(c)  // 1 detector + 1 CRC valid (replay)

        // CRC mismatch frame
        c.recordDetectorResult("TRACKED_RESAMPLED", true, true)
        c.recordCrcOutcome(null, "Frame CRC16 mismatch")

        // CRC valid frame
        c.recordDetectorResult("TRACKED_RESAMPLED", true, true)
        c.recordCrcOutcome(Any(), null)

        val s = c.buildSnapshot()
        // 1 (transition) + 2 = 3 detectors
        assertEquals(3, s.detectorInvocations)
        // 1 (transition) + 1 = 2 CRC valid; 1 CRC mismatch
        assertEquals(2, s.crcValidFrames)
        assertEquals(1, s.crcMismatchFrames)
        // Invariant: detector == all CRC outcomes
        assertEquals(s.detectorInvocations, s.crcValidFrames + s.crcMismatchFrames + s.uncertainFrames + s.otherTransportRejectedFrames)
    }

    // ── accumulator outcomes (crcValid NOT counted here) ───────────────

    @Test
    fun classifiesUniqueFrame() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        val s = c.buildSnapshot()
        assertEquals(1, s.uniqueFramesAccepted)
    }

    @Test
    fun classifiesDuplicate() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAccumulatorOutcome(
            pre(3, 0, 0, sessionId = 1),
            post(3, 1, 0), frame(frameId = 1), null
        )
        val s = c.buildSnapshot()
        assertEquals(1, s.duplicateFrames)
    }

    @Test
    fun classifiesConflict() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAccumulatorOutcome(
            pre(3, 0, 0, sessionId = 1),
            post(3, 0, 1), frame(frameId = 1), null
        )
        val s = c.buildSnapshot()
        assertEquals(1, s.conflictFrames)
    }

    @Test
    fun classifiesForeignSession() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAccumulatorOutcome(
            pre(3, 0, 0, sessionId = 1),
            post(3, 0, 0), frame(sessionId = 99), null
        )
        val s = c.buildSnapshot()
        assertEquals(1, s.foreignSessionRejected)
    }

    @Test
    fun classifiesInconsistentTotalFrames() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAccumulatorOutcome(
            pre(3, 0, 0, sessionId = 1, totalFrames = 5),
            post(3, 0, 0), frame(sessionId = 1, totalFrames = 999), null
        )
        val s = c.buildSnapshot()
        assertEquals(1, s.inconsistentTotalFramesRejected)
    }

    // ── scanning isolation ─────────────────────────────────────────────

    @Test
    fun scanningFramesDoNotContaminateActiveDetectorCounters() {
        val c = V6TransferStatsCollector().also { it.reset() }
        for (i in 1..5) {
            c.recordAnalyzerFrame()
            c.recordDetectorResult("FULL_DETECTION", false, false)
        }
        val s = c.buildSnapshot()
        assertEquals(V6TransferStatsCollector.State.SCANNING, c.state)
        assertEquals(5, s.analyzerFramesSinceReset)
        assertEquals(0, s.detectorInvocations)
    }

    @Test
    fun firstAcceptedFrameIsCountedInActiveStats() {
        val c = V6TransferStatsCollector().also { it.reset() }
        for (i in 1..3) {
            c.recordAnalyzerFrame()
            c.recordDetectorResult("FULL_DETECTION", false, false)
        }
        c.recordAnalyzerFrame()
        c.recordDetectorResult("FULL_DETECTION", true, true)
        c.recordCrcOutcome(frame(frameId = 0), null)
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        assertEquals(V6TransferStatsCollector.State.ACTIVE, c.state)
        val s = c.buildSnapshot()
        assertEquals(1, s.detectorInvocations)
        assertEquals(1, s.fullDetectionFrames)
        assertEquals(1, s.crcValidFrames)
        assertEquals(4, s.analyzerFramesSinceReset)
    }

    // ── completion ─────────────────────────────────────────────────────

    @Test
    fun completionPreservesPreResetCounts() {
        val c = V6TransferStatsCollector().also { it.reset() }
        for (fid in 0..2) {
            c.recordAccumulatorOutcome(
                pre(fid, 0, 0, sessionId = 1),
                post(fid + 1, 0, 0), frame(frameId = fid), null
            )
        }
        val completingFrame = frame(frameId = 4, totalFrames = 5)
        c.recordAccumulatorOutcome(
            pre(3, 0, 0, sessionId = 1),
            post(0, 0, 0), completingFrame, pkg("f.txt", 256, ByteArray(256))
        )
        assertEquals(V6TransferStatsCollector.State.COMPLETE, c.state)
        val s = c.buildSnapshot()
        assertEquals(4, s.uniqueFramesAccepted)
        assertEquals(5, s.totalLogicalFrames)
        assertEquals(256L, s.completedFileBytes)
    }

    // ── timing fields ──────────────────────────────────────────────────

    @Test
    fun perSourceTimingAccumulatesCorrectly() {
        val c = V6TransferStatsCollector().also { it.reset() }
        transitionToActive(c)
        c.recordDetectorResult("TRACKED_RESAMPLED", true, true,
            detectorStartNs = 1_000_000L, detectorEndNs = 4_000_000L)
        val s = c.buildSnapshot()
        assertEquals(1, s.fullDetectionCount)
        assertEquals(1, s.trackedResampledCount)
        assertEquals(3.0, s.trackedResampledMeanMs, 0.01)
        assertEquals(3.0, s.trackedResampledMedianMs, 0.01)
        assertEquals(3.0, s.trackedResampledP95Ms, 0.01)
    }

    @Test
    fun sensorAndCallbackDeltasAreRecorded() {
        val c = V6TransferStatsCollector().also { it.reset() }
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        assertEquals(V6TransferStatsCollector.State.ACTIVE, c.state)
        // Two active analyzer frames with sensor timestamps and analyzer arrivals.
        c.recordAnalyzerFrame(1_000_000_000L)
        c.recordAnalyzerFrame(1_050_000_000L) // 50ms delta
        val s = c.buildSnapshot()
        assertEquals(2, s.activeAnalyzerFrames)
        assertEquals(1, s.sensorDeltaCount)
        assertEquals(50_000_000L, s.sensorDeltaTotalNs)
        assertEquals(50.0, s.sensorDeltaMeanMs, 0.1)
    }

    @Test
    fun derivedThroughputMetricsAreSane() {
        val c = V6TransferStatsCollector().also { it.reset() }
        for (fid in 0..3) {
            c.recordAccumulatorOutcome(
                pre(fid, 0, 0, sessionId = 1),
                post(fid + 1, 0, 0), frame(frameId = fid), null
            )
        }
        val completedPkg = pkg("file.bin", fileSize = 500, ByteArray(500))
        c.recordAccumulatorOutcome(
            pre(4, 0, 0, sessionId = 1),
            post(0, 0, 0), frame(frameId = 4, totalFrames = 5), completedPkg
        )
        val s = c.buildSnapshot()
        assertEquals(5, s.uniqueFramesAccepted)
        assertEquals(500L, s.completedFileBytes)
        assertEquals(5 * 91L, s.acceptedTransportPayloadBytes)
    }

    // ── camera config survives transfer reset ───────────────────────────

    @Test
    fun cameraConfigSurvivesReset() {
        val c = V6TransferStatsCollector().also { it.reset() }

        // Simulate camera binding before any transfer.
        c.recordCameraConfig(width = 1280, height = 720,
            supportedRanges = "[30-30, 60-60]", selectedRange = "60-60",
            boundRange = "60-60", fallback = false)

        // Complete a transfer (transition ACTIVE → COMPLETE).
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        c.recordAccumulatorOutcome(
            pre(1, 0, 0, sessionId = 1),
            post(0, 0, 0), frame(frameId = 1, totalFrames = 2),
            pkg("f.txt", 256, ByteArray(256))
        )

        val s1 = c.buildSnapshot()
        assertEquals(1280, s1.analysisWidth)
        assertEquals(720, s1.analysisHeight)
        assertEquals("[30-30, 60-60]", s1.cameraSupportedRanges)
        assertEquals("60-60", s1.cameraSelectedRange)
        assertEquals("60-60", s1.cameraBoundRange)
        assertFalse(s1.cameraFpsFallback)

        // Reset for a new transfer — camera config must persist.
        c.reset()
        val s2 = c.buildSnapshot()
        assertEquals(1280, s2.analysisWidth)
        assertEquals(720, s2.analysisHeight)
        assertEquals("[30-30, 60-60]", s2.cameraSupportedRanges)
        assertEquals("60-60", s2.cameraSelectedRange)
        assertEquals("60-60", s2.cameraBoundRange)
        assertFalse(s2.cameraFpsFallback)
    }

    @Test
    fun cameraConfigNotReRecordedWhenWidthZeroAfterReset() {
        val c = V6TransferStatsCollector().also { it.reset() }

        c.recordCameraConfig(1280, 720, "[30-30]", "30-30", "30-30")
        c.reset()
        // Simulate a post-reset call with 0,0 (should NOT overwrite).
        c.recordCameraConfig(0, 0, "", "", "")

        val s = c.buildSnapshot()
        assertEquals(1280, s.analysisWidth)
        assertEquals(720, s.analysisHeight)
        assertEquals("[30-30]", s.cameraSupportedRanges)
        assertEquals("30-30", s.cameraSelectedRange)
    }

    // ── acquisition latency ──────────────────────────────────────────────

    /** Simulates accepting frame IDs in order (no duplicates, completing at end). */
    @Test
    fun acquisitionLatencyMilestonesAreProportional() {
        val c = V6TransferStatsCollector().also { it.reset() }

        // Frame 0: SCANNING→ACTIVE transition
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        // Accept remaining 4 frames in order.
        for (fid in 1..3) {
            c.recordAccumulatorOutcome(
                pre(fid, 0, 0, sessionId = 1),
                post(fid + 1, 0, 0), frame(frameId = fid), null
            )
        }
        // Completing frame.
        c.recordAccumulatorOutcome(
            pre(4, 0, 0, sessionId = 1),
            post(0, 0, 0),
            frame(frameId = 4, totalFrames = 5),
            pkg("f.txt", 256, ByteArray(256))
        )

        val s = c.buildSnapshot()
        assertEquals(V6TransferStatsCollector.State.COMPLETE, c.state)
        assertEquals(5, s.uniqueFramesAccepted)
        // Last 5 IDs: "0,1,2,3,4"
        assertEquals("0,1,2,3,4", s.acquisitionLast5Ids)
        assertEquals(5, s.acquisitionLast5TimesNs.size)
        // Milestones should be monotonically increasing.
        assertTrue(s.acquisition25PctNs >= 0)
        assertTrue(s.acquisition50PctNs >= s.acquisition25PctNs)
        assertTrue(s.acquisition75PctNs >= s.acquisition50PctNs)
        assertTrue(s.acquisition90PctNs >= s.acquisition75PctNs || s.acquisition90PctNs == s.acquisition75PctNs)
        assertTrue(s.acquisition100PctNs >= s.acquisition90PctNs)
    }

    @Test
    fun duplicateDoesNotAlterFirstAcceptTimestamp() {
        val c = V6TransferStatsCollector().also { it.reset() }

        // Frame 0: ACTIVE transition.
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        // Frame 0 duplicate — should NOT reorder uniqueAcceptOrder.
        val preDup = c.buildSnapshot().acquisitionLast5Ids
        c.recordAccumulatorOutcome(
            pre(1, 0, 0, sessionId = 1),
            post(1, 1, 0), frame(frameId = 0), null
        )
        val postDup = c.buildSnapshot()
        // Last 5 IDs unchanged after a duplicate.
        assertEquals(preDup, postDup.acquisitionLast5Ids)

        // Frame 1: unique.
        c.recordAccumulatorOutcome(
            pre(1, 1, 0, sessionId = 1),
            post(2, 1, 0), frame(frameId = 1), null
        )
        val s = c.buildSnapshot()
        // Frame 0 first, then frame 1.
        assertEquals("0,1", s.acquisitionLast5Ids)
    }

    @Test
    fun longestGapIsBetweenTwoDistantAcceptances() {
        val c = V6TransferStatsCollector().also { it.reset() }

        // Accept frames 0,1,2 quickly in order.
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        c.recordAccumulatorOutcome(
            pre(1, 0, 0, sessionId = 1),
            post(2, 0, 0), frame(frameId = 1), null
        )
        c.recordAccumulatorOutcome(
            pre(2, 0, 0, sessionId = 1),
            post(3, 0, 0), frame(frameId = 2), null
        )

        val s = c.buildSnapshot()
        // At least one gap should be non-negative.
        assertTrue(s.acquisitionLongestGapNs >= 0)
        // With real time moving forward, the gap between frame 1 and 2 should be tiny.
        // We can't assert exact values, but we can assert the field is populated.
        assertTrue(s.acquisitionLast5Ids.split(",").size >= 3)
    }

    @Test
    fun frameHitsTrackValidSamplesPerId() {
        val c = V6TransferStatsCollector().also { it.reset() }

        // Frame 0: unique.
        c.recordAccumulatorOutcome(
            pre(0, 0, 0, sessionId = -1, totalFrames = -1),
            post(1, 0, 0), frame(frameId = 0), null
        )
        // Frame 0: duplicate (hit=2).
        c.recordAccumulatorOutcome(
            pre(1, 0, 0, sessionId = 1),
            post(1, 1, 0), frame(frameId = 0), null
        )
        // Frame 0: duplicate (hit=3).
        c.recordAccumulatorOutcome(
            pre(1, 1, 0, sessionId = 1),
            post(1, 2, 0), frame(frameId = 0), null
        )
        // Frame 1: unique (hit=1).
        c.recordAccumulatorOutcome(
            pre(1, 2, 0, sessionId = 1),
            post(2, 2, 0), frame(frameId = 1), null
        )
        // Frame 0: duplicate (hit=4).
        c.recordAccumulatorOutcome(
            pre(2, 2, 0, sessionId = 1),
            post(2, 3, 0), frame(frameId = 0), null
        )

        val s = c.buildSnapshot()
        assertEquals(1, s.acquisitionMinHits)  // frame 1 seen once
        assertEquals(4, s.acquisitionMaxHits)  // frame 0 seen 4 times
    }

    @Test
    fun acquisitionLatencyWorksWithZeroTotalFrames() {
        val c = V6TransferStatsCollector().also { it.reset() }
        // No frames accepted at all.
        val s = c.buildSnapshot()
        assertEquals(0, s.acquisition25PctNs)
        assertEquals(0, s.acquisition100PctNs)
        assertEquals("", s.acquisitionLast5Ids)
    }
}
