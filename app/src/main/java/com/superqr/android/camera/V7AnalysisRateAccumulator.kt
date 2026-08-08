package com.superqr.android.camera

/**
 * Lightweight rolling-window completed-analysis rate counter.
 *
 * Accumulates completion timestamps over an approximately one-second window
 * and produces a stable observed analysis_fps value.
 *
 * Allocation-light: internally uses a ring buffer of timestamps.
 */
class V7AnalysisRateAccumulator(private val windowSize: Int = 64) {

    private val timestamps = LongArray(windowSize)
    private var writeIdx = 0
    private var count = 0

    /** Record a completed analysis. */
    fun recordCompletion(timestampNs: Long) {
        timestamps[writeIdx] = timestampNs
        writeIdx = (writeIdx + 1) % windowSize
        if (count < windowSize) count++
    }

    /** Compute analysis FPS over the rolling window. */
    fun computeFps(nowNs: Long = System.nanoTime()): Double {
        if (count < 2) return 0.0
        // Find the oldest timestamp still within ~2 seconds of now.
        // Walk backwards from writeIdx-1.
        val cutoffNs = nowNs - 2_000_000_000L
        var oldestIdx = writeIdx
        var validCount = 0
        for (i in 0 until count) {
            val idx = (writeIdx - 1 - i + windowSize) % windowSize
            if (timestamps[idx] >= cutoffNs) {
                oldestIdx = idx
                validCount++
            } else {
                break // older entries are too old
            }
        }
        if (validCount < 2) return 0.0
        val elapsedSec = (nowNs - timestamps[oldestIdx]) / 1_000_000_000.0
        if (elapsedSec <= 0.0) return 0.0
        return validCount.toDouble() / elapsedSec
    }

    /** Reset for a new scan. */
    fun reset() {
        writeIdx = 0
        count = 0
        timestamps.fill(0)
    }
}
