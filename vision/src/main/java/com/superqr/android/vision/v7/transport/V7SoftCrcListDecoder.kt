package com.superqr.android.vision.v7.transport

/**
 * Tiny CRC-guided list decoder for near-miss optical frames.
 *
 * The classifier already exposes a best and second-best symbol plus their
 * distances. When a frame is complete but CRC32 fails, try the second-best
 * symbol only at a small number of the most ambiguous current cells. We search
 * one-, two- and three-cell alternatives; CRC32 + full transport parsing remain
 * the final authority, so this cannot silently accept a guessed frame.
 *
 * This is intentionally bounded. It is not a substitute for the future V7 FEC
 * layer; it converts cheap near misses into useful frames without exploding CPU.
 */
object V7SoftCrcListDecoder {
    data class Outcome(
        val frame: V7TransportFrame?,
        val repairedSymbols: ByteArray?,
        val attempts: Int,
        val flips: Int,
    )

    private data class AmbiguousCell(
        val index: Int,
        val second: Byte,
        val ratio: Double,
    )

    fun repair(
        baseSymbols: ByteArray,
        currentBestSymbols: ByteArray,
        secondBestSymbols: ByteArray,
        bestDistances: IntArray,
        secondBestDistances: IntArray,
        profile: V7OpticalProfile,
        maxCells: Int = 9,
        maxFlips: Int = 3,
    ): Outcome {
        if (
            baseSymbols.size != profile.cellCount ||
            currentBestSymbols.size != profile.cellCount ||
            secondBestSymbols.size != profile.cellCount ||
            bestDistances.size != profile.cellCount ||
            secondBestDistances.size != profile.cellCount
        ) return Outcome(null, null, 0, 0)

        val maxSymbol = (1 shl profile.bitsPerCell) - 1
        val ambiguous = ArrayList<AmbiguousCell>(profile.cellCount)
        for (i in baseSymbols.indices) {
            val base = baseSymbols[i].toInt()
            val current = currentBestSymbols[i].toInt()
            val second = secondBestSymbols[i].toInt()
            val d1 = bestDistances[i]
            val d2 = secondBestDistances[i]

            // secondBest describes the current optical observation. Do not apply
            // it to a cell that temporal recovery has already replaced.
            if (base !in 0..maxSymbol || current !in 0..maxSymbol || base != current) continue
            if (second !in 0..maxSymbol || second == base) continue
            if (d1 < 0 || d2 <= 0 || d2 == Int.MAX_VALUE) continue
            val ratio = d1.toDouble() / d2.toDouble()
            ambiguous.add(AmbiguousCell(i, secondBestSymbols[i], ratio))
        }

        if (ambiguous.isEmpty()) return Outcome(null, null, 0, 0)
        ambiguous.sortByDescending { it.ratio }
        val selected = if (ambiguous.size > maxCells) ambiguous.subList(0, maxCells) else ambiguous
        val work = baseSymbols.copyOf()
        var attempts = 0

        fun tryCurrent(flips: Int): Outcome? {
            val bytes = V7Transport.symbolsToBytes(work, profile) ?: return null
            attempts++
            val frame = try {
                V7Transport.parseFrame(bytes, profile)
            } catch (_: V7TransportError) {
                null
            }
            return if (frame != null) Outcome(frame, work.copyOf(), attempts, flips) else null
        }

        fun search(depth: Int, start: Int, targetDepth: Int): Outcome? {
            if (depth == targetDepth) return tryCurrent(targetDepth)
            val remaining = targetDepth - depth
            val lastStart = selected.size - remaining
            for (p in start..lastStart) {
                val cell = selected[p]
                val old = work[cell.index]
                work[cell.index] = cell.second
                val found = search(depth + 1, p + 1, targetDepth)
                work[cell.index] = old
                if (found != null) return found
            }
            return null
        }

        val cappedFlips = maxFlips.coerceIn(1, 3)
        for (flips in 1..cappedFlips) {
            if (selected.size < flips) break
            val found = search(0, 0, flips)
            if (found != null) return found.copy(attempts = attempts)
        }
        return Outcome(null, null, attempts, 0)
    }
}
