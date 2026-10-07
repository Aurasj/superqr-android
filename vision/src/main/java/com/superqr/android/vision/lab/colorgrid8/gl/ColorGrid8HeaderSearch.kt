package com.superqr.android.vision.lab.colorgrid8.gl

import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Analyzer
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec

/** A fixed-size acquisition search; offsets change sampling, never decoded bits. */
object ColorGrid8HeaderSearch {
    const val OFFSET_STEPS = 5
    const val OFFSETS_PER_ROTATION = OFFSET_STEPS * OFFSET_STEPS
    const val ROTATIONS = 4
    const val CANDIDATES = ROTATIONS * OFFSETS_PER_ROTATION
    const val WIDTH = ColorGrid8Spec.HEADER_CELLS
    const val HEIGHT = CANDIDATES * ColorGrid8Spec.HEADER_ROWS
    const val READBACK_BYTES = WIDTH * HEIGHT * 4

    data class Candidate(val rotation: Int, val dx: Float, val dy: Float) {
        val distanceSquared: Float get() = dx * dx + dy * dy
    }

    data class Selection(val candidate: Candidate, val probe: ColorGrid8Analyzer.HeaderProbe)
    data class Result(val selection: Selection?, val bestProbe: ColorGrid8Analyzer.HeaderProbe)

    fun candidate(index: Int): Candidate {
        require(index in 0 until CANDIDATES)
        val offset = index % OFFSETS_PER_ROTATION
        return Candidate(index / OFFSETS_PER_ROTATION,
            (offset % OFFSET_STEPS - 2) * 0.25f,
            (offset / OFFSET_STEPS - 2) * 0.25f)
    }

    fun select(profile: ColorGrid8Profile, luma: ByteArray, analyzer: ColorGrid8Analyzer): Result {
        require(luma.size == WIDTH * HEIGHT)
        var selection: Selection? = null
        var bestProbe: ColorGrid8Analyzer.HeaderProbe? = null
        repeat(CANDIDATES) { index ->
            val probe = analyzer.probeCanonicalHeader(luma, WIDTH, index * WIDTH * ColorGrid8Spec.HEADER_ROWS)
            val previous = bestProbe
            if (previous == null || probe.score < previous.score ||
                (probe.score == previous.score && probe.contrast > previous.contrast)) bestProbe = probe
            val header = probe.header ?: return@repeat
            if (header.version != profile.version || header.profileId != profile.profileId ||
                header.fps != profile.fps || header.seed != profile.seed) return@repeat
            val candidate = candidate(index)
            val current = selection
            // Prefer cleanly separated header bits, with the smallest geometric
            // correction breaking ties. Both repeated rows must pass unchanged.
            if (current == null || probe.minimumBitMargin > current.probe.minimumBitMargin ||
                (probe.minimumBitMargin == current.probe.minimumBitMargin &&
                    candidate.distanceSquared < current.candidate.distanceSquared)) {
                selection = Selection(candidate, probe)
            }
        }
        return Result(selection, bestProbe!!)
    }
}
