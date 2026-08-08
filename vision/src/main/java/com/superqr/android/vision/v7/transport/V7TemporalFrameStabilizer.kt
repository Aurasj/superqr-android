package com.superqr.android.vision.v7.transport

import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import java.util.LinkedHashMap

/**
 * Small, bounded temporal recovery cache keyed by the decoded V7 frame header.
 *
 * It never combines observations from different logical frames. The header must
 * decode first, so sender frame changes cannot smear symbols together. Current
 * confident cells are kept; only current erasures are filled from prior
 * observations of the same (session, frame, profile). Strong historical
 * consensus may also override an isolated conflicting current symbol.
 *
 * CRC32 remains the final authority: recovered candidates are never accepted
 * merely because this stabilizer produced a complete symbol vector.
 */
class V7TemporalFrameStabilizer(
    private val maxTrackedFrames: Int = 32,
    private val maxAgeNs: Long = 30_000_000_000L,
) {
    data class Key(
        val sessionId: Int,
        val frameId: Int,
        val totalFrames: Int,
        val profileId: Int,
    )

    data class MergeResult(
        val symbols: ByteArray,
        val observations: Int,
        val filledErasures: Int,
        val overriddenConflicts: Int,
        val remainingErasures: Int,
        val consensusCells: Int,
    )

    private data class State(
        val candidate: ByteArray,
        val votes: ByteArray,
        var observations: Int,
        var lastSeenNs: Long,
    )

    private val states = object : LinkedHashMap<Key, State>(maxTrackedFrames + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, State>?): Boolean {
            return size > maxTrackedFrames
        }
    }

    @Synchronized
    fun reset() {
        states.clear()
    }

    @Synchronized
    fun merge(
        header: V7FrameHeader,
        currentSymbols: ByteArray,
        nowNs: Long = System.nanoTime(),
    ): MergeResult {
        pruneExpired(nowNs)
        val key = Key(header.sessionId, header.frameId, header.totalFrames, header.profileId)
        val state = states.getOrPut(key) {
            State(
                candidate = ByteArray(currentSymbols.size) { V7SoftClassifier.ERASURE_MARKER },
                votes = ByteArray(currentSymbols.size),
                observations = 0,
                lastSeenNs = nowNs,
            )
        }
        if (state.candidate.size != currentSymbols.size) {
            states.remove(key)
            return merge(header, currentSymbols, nowNs)
        }

        state.observations++
        state.lastSeenNs = nowNs

        for (i in currentSymbols.indices) {
            val observed = currentSymbols[i]
            if (observed == V7SoftClassifier.ERASURE_MARKER) continue
            val candidate = state.candidate[i]
            val vote = state.votes[i].toInt() and 0xFF
            when {
                candidate == V7SoftClassifier.ERASURE_MARKER -> {
                    state.candidate[i] = observed
                    state.votes[i] = 1
                }
                candidate == observed -> {
                    state.votes[i] = minOf(127, vote + 1).toByte()
                }
                vote > 1 -> {
                    state.votes[i] = (vote - 1).toByte()
                }
                else -> {
                    state.candidate[i] = observed
                    state.votes[i] = 1
                }
            }
        }

        val merged = currentSymbols.copyOf()
        var filled = 0
        var overridden = 0
        var remaining = 0
        var consensus = 0
        for (i in merged.indices) {
            val candidate = state.candidate[i]
            val vote = state.votes[i].toInt() and 0xFF
            if (candidate != V7SoftClassifier.ERASURE_MARKER && vote >= 2) consensus++

            if (merged[i] == V7SoftClassifier.ERASURE_MARKER) {
                // One prior observation is enough to propose a fill; CRC will
                // reject a bad proposal. Two+ observations are marked consensus.
                if (candidate != V7SoftClassifier.ERASURE_MARKER && vote >= 1) {
                    merged[i] = candidate
                    filled++
                } else {
                    remaining++
                }
            } else if (
                candidate != V7SoftClassifier.ERASURE_MARKER &&
                candidate != merged[i] &&
                vote >= 3
            ) {
                // Strong historical consensus can correct one confidently but
                // inconsistently classified current observation.
                merged[i] = candidate
                overridden++
            }
        }

        return MergeResult(
            symbols = merged,
            observations = state.observations,
            filledErasures = filled,
            overriddenConflicts = overridden,
            remainingErasures = remaining,
            consensusCells = consensus,
        )
    }

    @Synchronized
    fun trackedFrameCount(): Int = states.size

    private fun pruneExpired(nowNs: Long) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowNs - entry.value.lastSeenNs > maxAgeNs) iterator.remove()
        }
    }
}
