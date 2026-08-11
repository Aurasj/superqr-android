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
 * frame. Search begins with QR and alternates only while neither path has real
 * geometric evidence. Once either detector sees a plausible carrier/QR, that
 * path receives consecutive frames so acquisition can stabilize before we try
 * the opposite PHY.
 *
 * A bounded GRID TRACK_HOLD may retain an existing GRID lock while geometry is
 * recovering. Cold carrier-like hypotheses may still keep unlocked GRID search
 * consecutive, but callers can explicitly prevent them from extending a lock.
 * Likewise, a QR quadrangle without a decoded payload is still real QR
 * acquisition evidence; switching to GRID on the next frame starves dense
 * V27/V40 detection and makes the UI flicker between QR and search states.
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

    /**
     * A validated DONE envelope is a hard campaign boundary. Release the current
     * PHY lock immediately and give the opposite PHY a bounded probe window so a
     * GRID -> QR (or QR -> GRID) campaign transition cannot be starved by stale
     * geometric evidence from the completed run.
     */
    fun completed(path: Phase1AnalysisPath) {
        lockedPath = null
        val opposite = opposite(path)
        nextSearchPath = opposite
        misses = 0
        transitionProbePath = opposite
        transitionProbesRemaining = transitionProbeFrames.coerceAtLeast(1)
    }

    fun missed(
        path: Phase1AnalysisPath,
        carrierCandidate: Boolean = false,
        qrCandidate: Boolean = false,
        retainLockedPath: Boolean? = null,
    ) {
        val hasPathEvidence = when (path) {
            Phase1AnalysisPath.GRID -> carrierCandidate
            Phase1AnalysisPath.QR -> qrCandidate
        }

        if (lockedPath == path) {
            // Locked-path retention is stricter than unlocked acquisition. GRID
            // callers use this override so only real TRACK_HOLD evidence can
            // extend a previous GRID lock; cold carrier-like hypotheses cannot.
            val shouldRetainLock = retainLockedPath ?: hasPathEvidence
            if (shouldRetainLock) {
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

        nextSearchPath = if (hasPathEvidence) path else opposite(path)
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
