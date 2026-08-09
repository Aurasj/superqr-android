package com.superqr.android.ui.phase1

/** Hard real-time guardrails for the physical PHY receiver. */
object Phase1AnalysisPolicy {
    const val TARGET_WIDTH = 1280
    const val TARGET_HEIGHT = 720
    const val MAX_ANALYSIS_PIXELS = 1280 * 960
    const val MIN_WARMUP_FRAMES = 8
    const val MIN_WARMUP_NS = 750_000_000L

    fun accepts(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_ANALYSIS_PIXELS.toLong()

    fun warmupComplete(startedNs: Long, nowNs: Long, deliveredFrames: Int): Boolean =
        deliveredFrames >= MIN_WARMUP_FRAMES && nowNs - startedNs >= MIN_WARMUP_NS
}

enum class Phase1AnalysisPath { GRID, QR }

/**
 * Avoids running both expensive acquisition algorithms serially on every frame.
 * A locked carrier is favored until several consecutive misses; while searching,
 * a geometry candidate keeps the next attempt on the grid path so sync can settle.
 */
class Phase1AcquisitionScheduler(private val unlockAfterMisses: Int = 5) {
    private var lockedPath: Phase1AnalysisPath? = null
    private var nextSearchPath = Phase1AnalysisPath.GRID
    private var misses = 0

    val path: Phase1AnalysisPath
        get() = lockedPath ?: nextSearchPath

    val state: String
        get() = lockedPath?.let { "${it.name}_LOCKED" } ?: "SEARCH_${nextSearchPath.name}"

    fun locked(path: Phase1AnalysisPath) {
        lockedPath = path
        nextSearchPath = path
        misses = 0
    }

    fun missed(path: Phase1AnalysisPath, carrierCandidate: Boolean = false) {
        if (lockedPath == path) {
            misses++
            if (misses >= unlockAfterMisses) {
                lockedPath = null
                nextSearchPath = if (path == Phase1AnalysisPath.GRID) Phase1AnalysisPath.QR else Phase1AnalysisPath.GRID
                misses = 0
            }
            return
        }
        misses = 0
        nextSearchPath = when {
            path == Phase1AnalysisPath.GRID && carrierCandidate -> Phase1AnalysisPath.GRID
            path == Phase1AnalysisPath.GRID -> Phase1AnalysisPath.QR
            else -> Phase1AnalysisPath.GRID
        }
    }

    fun reset() {
        lockedPath = null
        nextSearchPath = Phase1AnalysisPath.GRID
        misses = 0
    }
}
