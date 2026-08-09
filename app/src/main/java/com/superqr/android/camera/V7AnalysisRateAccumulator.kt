package com.superqr.android.camera

/**
 * Lightweight rolling-window completed-analysis rate counter.
 *
 * FPS is derived from intervals between completion events, not from event count
 * divided by the span from the first event. With two completions 100 ms apart
 * there is one measured interval, therefore the observed rate is 10 fps.
 */
class V7AnalysisRateAccumulator(private val windowSize: Int = 64) {

    private val timestamps = LongArray(windowSize)
    private var writeIdx = 0
    private var count = 0

    fun recordCompletion(timestampNs: Long) {
        timestamps[writeIdx] = timestampNs
        writeIdx = (writeIdx + 1) % windowSize
        if (count < windowSize) count++
    }

    fun computeFps(nowNs: Long = System.nanoTime()): Double {
        if (count < 2) return 0.0
        val cutoffNs = nowNs - 2_000_000_000L
        val latestIdx = (writeIdx - 1 + windowSize) % windowSize
        val latestNs = timestamps[latestIdx]
        if (latestNs < cutoffNs) return 0.0

        var oldestIdx = latestIdx
        var validCount = 1
        for (i in 1 until count) {
            val idx = (writeIdx - 1 - i + windowSize) % windowSize
            if (timestamps[idx] >= cutoffNs) {
                oldestIdx = idx
                validCount++
            } else {
                break
            }
        }
        if (validCount < 2) return 0.0
        val elapsedSec = (latestNs - timestamps[oldestIdx]) / 1_000_000_000.0
        if (elapsedSec <= 0.0) return 0.0
        return (validCount - 1).toDouble() / elapsedSec
    }

    fun reset() {
        writeIdx = 0
        count = 0
        timestamps.fill(0)
    }
}
