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
 *
 * Search starts with QR because running the custom GRID cold contour finder on a
 * dense standard-QR image is the worst-case direction: thousands of nested
 * contours can be generated before the scheduler gets a chance to switch paths.
 * A single QR miss on a GRID carrier simply hands the next frame to GRID during
 * the four-second READY guard.
 *
 * Once GRID is locked, the V7 grid acquirer itself holds a proven homography for
 * two transient sync misses and would enter its expensive cold contour search on
 * the third miss. This scheduler deliberately hands a lost GRID lock to QR after
 * two misses, before that cold search can run on a changed QR carrier. A short
 * bounded transition probe gives QR several decode opportunities; if it is not a
 * QR transition, normal alternating search resumes quickly.
 */
class Phase1AcquisitionScheduler(
    private val unlockAfterMisses: Int = 2,
    private val transitionProbeFrames: Int = 3,
) {
    private var lockedPath: Phase1AnalysisPath? = null
    private var nextSearchPath = Phase1AnalysisPath.QR
    private var misses = 0
    private var transitionProbePath: Phase1AnalysisPath? = null
    private var transitionProbesRemaining = 0

    val path: Phase1AnalysisPath
        get() = lockedPath ?: nextSearchPath

    val state: String
        get() = lockedPath?.let { "${it.name}_LOCKED" } ?: when {
            transitionProbePath == nextSearchPath && transitionProbesRemaining > 0 ->
                "TRANSITION_${nextSearchPath.name}_PROBE"
            else -> "SEARCH_${nextSearchPath.name}"
        }

    fun locked(path: Phase1AnalysisPath) {
        lockedPath = path
        nextSearchPath = path
        misses = 0
        transitionProbePath = null
        transitionProbesRemaining = 0
    }

    fun missed(path: Phase1AnalysisPath, carrierCandidate: Boolean = false) {
        if (lockedPath == path) {
            misses++
            if (misses >= unlockAfterMisses) {
                lockedPath = null
                val opposite = opposite(path)
                nextSearchPath = opposite
                misses = 0
                transitionProbePath = opposite
                transitionProbesRemaining = transitionProbeFrames.coerceAtLeast(1)
            }
            return
        }

        misses = 0
        if (transitionProbePath == path && transitionProbesRemaining > 0) {
            transitionProbesRemaining--
            if (transitionProbesRemaining > 0) {
                nextSearchPath = path
                return
            }
            transitionProbePath = null
        }

        nextSearchPath = when {
            path == Phase1AnalysisPath.GRID && carrierCandidate -> Phase1AnalysisPath.GRID
            else -> opposite(path)
        }
    }

    fun reset() {
        lockedPath = null
        nextSearchPath = Phase1AnalysisPath.QR
        misses = 0
        transitionProbePath = null
        transitionProbesRemaining = 0
    }

    private fun opposite(path: Phase1AnalysisPath): Phase1AnalysisPath =
        if (path == Phase1AnalysisPath.GRID) Phase1AnalysisPath.QR else Phase1AnalysisPath.GRID
}
