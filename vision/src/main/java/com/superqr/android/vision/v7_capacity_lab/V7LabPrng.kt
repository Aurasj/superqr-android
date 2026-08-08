package com.superqr.android.vision.v7_capacity_lab

/**
 * LAB-ONLY Xorshift32 PRNG.
 *
 * Implements exact unsigned 32-bit Xorshift32 according to the V7 Capacity Lab
 * manifest. Does NOT select the final V7 protocol PRNG.
 *
 * Key invariants (from lab_manifest.json prng section):
 * - State is a 32-bit unsigned integer
 * - All operations masked to 0xFFFFFFFF
 * - Seed = 0 is rejected
 * - Symbol generation: (next_state() >>> 16) % (1 << bits_per_cell)
 * - State is continuous across cells and data frames
 * - Calibration frames do NOT consume PRNG state
 */
class V7LabPrng(seed: Int) {

    private var state: Int

    init {
        val masked = seed and 0xFFFFFFFF.toInt()
        require(masked != 0) { "Xorshift32 seed must be non-zero (got $seed, masked=$masked)" }
        // Reinterpret as unsigned: state is always kept as an Int whose bits represent
        // a uint32_t. All operations use unsigned semantics via .toInt() after masks.
        this.state = masked
    }

    /** Return next 32-bit unsigned value. */
    fun next(): Int {
        var x = state
        // All shifts use Kotlin Int semantics; masking after left shifts keeps 32-bit.
        x = x xor ((x shl 13) and 0xFFFFFFFF.toInt())
        x = x xor (x ushr 17)
        x = x xor ((x shl 5) and 0xFFFFFFFF.toInt())
        state = x
        return x
    }

    /** Return next symbol index in [0, 1 << bitsPerCell). */
    fun nextSymbol(bitsPerCell: Int): Int {
        val symbolCount = 1 shl bitsPerCell
        // Upper 16 bits of the 32-bit output, modulo symbol count.
        // (next() >>> 16) is always non-negative in Kotlin (unsigned shift).
        return ((next() ushr 16) and 0xFFFF) % symbolCount
    }

    companion object {
        /** Reference seeds from the manifest. */
        val REFERENCE_SEEDS = intArrayOf(42, 7, 13, 99)

        /**
         * Generate a flat row-major expected symbol array for one data frame.
         * Uses a fresh PRNG at the given seed. The PRNG state advances across
         * all cells in row-major order.
         */
        fun generateExpectedSymbols(
            gridSize: Int,
            seed: Int,
            bitsPerCell: Int
        ): ByteArray {
            val prng = V7LabPrng(seed)
            val totalCells = gridSize * gridSize
            val result = ByteArray(totalCells)
            for (i in 0 until totalCells) {
                result[i] = prng.nextSymbol(bitsPerCell).toByte()
            }
            return result
        }

        /**
         * Generate expected symbols for a full sequence: calibration frames
         * (solid fills, do NOT consume PRNG) + data frames (PRNG-filled,
         * continuous state).
         *
         * Returns only the data-frame symbols concatenated in order.
         * Calibration frame symbols are returned separately.
         */
        fun generateSequenceSymbols(
            gridSize: Int,
            seed: Int,
            bitsPerCell: Int,
            symbolCount: Int,
            numCalibrationFrames: Int,
            numDataFrames: Int
        ): Pair<List<ByteArray>, List<ByteArray>> {
            val totalCells = gridSize * gridSize
            // Calibration frames: solid fill, no PRNG consumption
            val calFrames = mutableListOf<ByteArray>()
            for (f in 0 until numCalibrationFrames) {
                val symIdx = (f % symbolCount).toByte()
                calFrames.add(ByteArray(totalCells) { symIdx })
            }
            // Data frames: PRNG-filled, continuous state
            val prng = V7LabPrng(seed)
            val dataFrames = mutableListOf<ByteArray>()
            for (f in 0 until numDataFrames) {
                val frame = ByteArray(totalCells)
                for (i in 0 until totalCells) {
                    frame[i] = prng.nextSymbol(bitsPerCell).toByte()
                }
                dataFrames.add(frame)
            }
            return Pair(calFrames, dataFrames)
        }
    }
}
