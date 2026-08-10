package com.superqr.android.phase1

/** Hard real-time guardrails for the physical PHY receiver. */
object Phase1AnalysisPolicy {
    const val TARGET_WIDTH = 1280
    const val TARGET_HEIGHT = 960
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
 * Keeps expensive QR and GRID acquisition paths from running serially on every
 * frame. Search begins with QR, alternates while unlocked, and hands a truly
 * lost GRID lock to a short QR transition probe before allowing another cold
 * GRID contour search.
 *
 * A carrier-like miss while GRID is already locked is deliberately NOT treated
 * as a lost lock. V7CarrierAcquirer uses these frames for bounded TRACK_HOLD /
 * optical-flow recovery during phone motion or scale change. Switching to QR
 * during that hold starves the tracker of consecutive frames and can force an
 * unnecessary 150-250 ms cold contour acquisition.
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
            // GRID's carrier acquirer intentionally emits carrierLike=true for
            // TRACK_HOLD and other bounded recovery evidence. Keep feeding GRID
            // consecutive frames while that evidence exists instead of jumping
            // to QR and destroying motion/scale continuity.
            if (path == Phase1AnalysisPath.GRID && carrierCandidate) {
                misses = 0
                nextSearchPath = path
                return
            }

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
