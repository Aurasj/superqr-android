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
 * geometric evidence. Weak cold geometry receives a small bounded run of
 * consecutive frames so acquisition can stabilize, but it may not monopolize
 * search indefinitely without a valid run envelope.
 *
 * A bounded GRID TRACK_HOLD may retain an existing GRID lock while geometry is
 * recovering. Cold carrier-like hypotheses may still keep unlocked GRID search
 * consecutive, but callers can explicitly prevent them from extending a lock.
 * Likewise, a QR quadrangle without a decoded payload is still real QR
 * acquisition evidence during ordinary acquisition, subject to the same cold
 * evidence bound.
 *
 * A validated DONE envelope is different: it starts a neutral run-boundary
 * search. The just-completed PHY remains selected while DONE is still visible;
 * after the surface changes, misses alternate GRID/QR regardless of weak
 * geometric evidence until a new valid envelope locks the next run. This avoids
 * assuming that the next campaign run must use the opposite PHY.
 */
class Phase1AcquisitionScheduler(
    private val unlockAfterMisses: Int = 2,
    private val transitionProbeFrames: Int = 3,
    private val coldEvidenceFrames: Int = 3,
) {
    private var lockedPath: Phase1AnalysisPath? = null
    private var nextSearchPath = Phase1AnalysisPath.QR
    private var misses = 0
    private var transitionProbePath: Phase1AnalysisPath? = null
    private var transitionProbesRemaining = 0
    private var boundarySearch = false
    private var coldEvidencePath: Phase1AnalysisPath? = null
    private var coldEvidenceMisses = 0

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
        boundarySearch = false
        clearColdEvidence()
    }

    /**
     * Mark a validated campaign boundary without guessing the next run's PHY.
     * Keep the completed path selected while its DONE surface is still visible.
     * Once that surface changes and the current path misses, boundary search
     * alternates paths until a new valid envelope calls [locked].
     */
    fun completed(path: Phase1AnalysisPath) {
        lockedPath = null
        nextSearchPath = path
        misses = 0
        transitionProbePath = null
        transitionProbesRemaining = 0
        boundarySearch = true
        clearColdEvidence()
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
                clearColdEvidence()
            }
            return
        }

        // At a validated run boundary, weak geometry from the old/new surface
        // must not pin either PHY. Alternate on every miss until a new run
        // produces a valid envelope and locks its real path.
        if (boundarySearch) {
            misses = 0
            transitionProbePath = null
            transitionProbesRemaining = 0
            clearColdEvidence()
            nextSearchPath = opposite(path)
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

        if (!hasPathEvidence) {
            clearColdEvidence()
            nextSearchPath = opposite(path)
            return
        }

        if (coldEvidencePath == path) {
            coldEvidenceMisses++
        } else {
            coldEvidencePath = path
            coldEvidenceMisses = 1
        }

        if (coldEvidenceMisses >= coldEvidenceFrames.coerceAtLeast(1)) {
            clearColdEvidence()
            nextSearchPath = opposite(path)
        } else {
            nextSearchPath = path
        }
    }

    fun reset() {
        lockedPath = null
        nextSearchPath = Phase1AnalysisPath.QR
        misses = 0
        transitionProbePath = null
        transitionProbesRemaining = 0
        boundarySearch = false
        clearColdEvidence()
    }

    private fun clearColdEvidence() {
        coldEvidencePath = null
        coldEvidenceMisses = 0
    }

    private fun opposite(path: Phase1AnalysisPath): Phase1AnalysisPath =
        if (path == Phase1AnalysisPath.GRID) Phase1AnalysisPath.QR else Phase1AnalysisPath.GRID
}
