package com.superqr.android.camera

/** Lightweight rolling-window completed-event rate counter. */
class AnalysisRateAccumulator(private val windowSize: Int = 64) {
    private val timestamps = LongArray(windowSize)
    private var writeIndex = 0
    private var count = 0

    fun recordCompletion(timestampNs: Long) {
        timestamps[writeIndex] = timestampNs
        writeIndex = (writeIndex + 1) % windowSize
        if (count < windowSize) count++
    }

    fun computeFps(nowNs: Long = System.nanoTime()): Double {
        if (count < 2) return 0.0
        val cutoffNs = nowNs - 2_000_000_000L
        val latestIndex = (writeIndex - 1 + windowSize) % windowSize
        val latestNs = timestamps[latestIndex]
        if (latestNs < cutoffNs) return 0.0

        var oldestIndex = latestIndex
        var validCount = 1
        for (offset in 1 until count) {
            val index = (writeIndex - 1 - offset + windowSize) % windowSize
            if (timestamps[index] >= cutoffNs) {
                oldestIndex = index
                validCount++
            } else {
                break
            }
        }
        if (validCount < 2) return 0.0
        val elapsedSeconds = (latestNs - timestamps[oldestIndex]) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) return 0.0
        return (validCount - 1).toDouble() / elapsedSeconds
    }

    fun reset() {
        writeIndex = 0
        count = 0
        timestamps.fill(0)
    }
}
