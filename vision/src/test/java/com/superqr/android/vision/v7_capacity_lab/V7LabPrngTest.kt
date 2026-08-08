package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.CRC32

class V7LabPrngTest {

    // ---- Manifest golden values ----

    @Test
    fun `V6 golden first_20 symbols`() {
        val symbols = V7LabPrng.generateExpectedSymbols(
            gridSize = 20, seed = 42, bitsPerCell = 2
        )
        val expected = intArrayOf(1, 2, 3, 0, 0, 2, 3, 3, 2, 1, 0, 0, 0, 3, 1, 1, 2, 0, 2, 1)
        for (i in expected.indices) {
            assertEquals("Symbol $i", expected[i], symbols[i].toInt() and 0xFF)
        }
    }

    @Test
    fun `V6 golden CRC-32`() {
        val symbols = V7LabPrng.generateExpectedSymbols(
            gridSize = 20, seed = 42, bitsPerCell = 2
        )
        val crc = CRC32()
        crc.update(symbols)
        assertEquals("BEAFE8A7", "%08X".format(crc.value))
    }

    @Test
    fun `seed zero rejected`() {
        try {
            V7LabPrng(0)
            fail("Expected IllegalArgumentException for seed=0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `seed zero after masking rejected`() {
        // 0x100000000 becomes 0 after masking to 32 bits, should be rejected
        try {
            V7LabPrng(0x100000000.toInt())
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `first 20 symbols match 8-color palette at seed 42`() {
        val prng = V7LabPrng(42)
        val expected = intArrayOf(5, 2, 7, 0, 4, 2, 7, 7, 2, 5, 0, 0, 4, 7, 5, 1, 2, 4, 6, 5)
        for (i in expected.indices) {
            assertEquals("Symbol $i", expected[i], prng.nextSymbol(3))
        }
    }

    // ---- First data-frame hash (40x40, v6_reference_4, seed=42) ----

    @Test
    fun `first data frame 40x40 v6_reference_4 SHA-256`() {
        val symbols = V7LabPrng.generateExpectedSymbols(40, 42, 2)
        val sha256 = sha256(symbols)
        assertEquals(
            "06b045348c4193e1be03a704889200afdf17ef12d38804684e2387b2b306f518",
            sha256
        )
    }

    @Test
    fun `first data frame 40x40 candidate_8_a SHA-256`() {
        val symbols = V7LabPrng.generateExpectedSymbols(40, 42, 3)
        val sha256 = sha256(symbols)
        assertEquals(
            "ee4a44ebbe82bb9097f15edc609ef927e2400d61a44a5c66edabf366044c279f",
            sha256
        )
    }

    // ---- Multiple consecutive data-frame hashes (40x40, v6_reference_4) ----

    @Test
    fun `consecutive data frames 40x40 v6_reference_4`() {
        val expectedHashes = arrayOf(
            "06b045348c4193e1be03a704889200afdf17ef12d38804684e2387b2b306f518",
            "8b4096f231ec0e9fc8fa8ef697687265dc622b5ad4853d9cc9412e853f5946ed",
            "89f7a592e12b58285e24533c8d082e3f421bc2512fe29a71cfd316c681e027b0",
            "948981c001649a1a6bb1453bfdcc57f9dd96523e4c92c27ae9397ca325d28be1"
        )
        val prng = V7LabPrng(42)
        val totalCells = 40 * 40
        for (f in expectedHashes.indices) {
            val frame = ByteArray(totalCells)
            for (i in 0 until totalCells) {
                frame[i] = prng.nextSymbol(2).toByte()
            }
            assertEquals("Frame $f SHA-256", expectedHashes[f], sha256(frame))
        }
    }

    // ---- Sequence symbol SHA-256 (full calibration + data concatenation) ----

    @Test
    fun `full sequence hash 40x40 v6_reference_4`() {
        val (calFrames, dataFrames) = V7LabPrng.generateSequenceSymbols(
            gridSize = 40, seed = 42, bitsPerCell = 2,
            symbolCount = 4, numCalibrationFrames = 4, numDataFrames = 4
        )
        assertEquals(4, calFrames.size)
        assertEquals(4, dataFrames.size)

        val allBytes = concatAll(calFrames + dataFrames)
        assertEquals(
            "5fec3fb13f36af28451f1b1f7de99e6c0187249feff635398fcc5f411ebceaf6",
            sha256(allBytes)
        )
    }

    @Test
    fun `full sequence hash 40x40 candidate_8_a`() {
        val (calFrames, dataFrames) = V7LabPrng.generateSequenceSymbols(
            gridSize = 40, seed = 42, bitsPerCell = 3,
            symbolCount = 8, numCalibrationFrames = 8, numDataFrames = 4
        )
        assertEquals(8, calFrames.size)
        assertEquals(4, dataFrames.size)

        val allBytes = concatAll(calFrames + dataFrames)
        assertEquals(
            "855b44bcad0d25891d8e28270bcb4b2f765a9e744e398bcaabbaf14854ee5fdb",
            sha256(allBytes)
        )
    }

    // ---- Calibration frames do NOT consume PRNG state ----

    @Test
    fun `calibration frames do not consume PRNG state`() {
        // Generate with calibration frames
        val (_, dataWithCal) = V7LabPrng.generateSequenceSymbols(
            gridSize = 40, seed = 42, bitsPerCell = 2,
            symbolCount = 4, numCalibrationFrames = 4, numDataFrames = 1
        )
        // Generate without calibration frames
        val dataNoCal = V7LabPrng.generateExpectedSymbols(40, 42, 2)

        // First data frame should be identical regardless of preceding calibration
        assertArrayEquals(
            "Data frame should be identical with or without preceding calibration frames",
            dataNoCal, dataWithCal[0]
        )
    }

    // ---- PRNG continuity across data frames ----

    @Test
    fun `PRNG state continuous across data frames`() {
        // Generate 2 data frames with continuous state
        val (_, dataFrames) = V7LabPrng.generateSequenceSymbols(
            gridSize = 40, seed = 42, bitsPerCell = 2,
            symbolCount = 4, numCalibrationFrames = 0, numDataFrames = 2
        )

        // Generate same total cells with a single PRNG
        val prng = V7LabPrng(42)
        val totalCells = 40 * 40 * 2
        val allSymbols = ByteArray(totalCells)
        for (i in 0 until totalCells) {
            allSymbols[i] = prng.nextSymbol(2).toByte()
        }

        // Concatenate the two frames and compare
        val concatFrames = dataFrames[0] + dataFrames[1]
        assertArrayEquals("Concatenated frames should match continuous PRNG output", allSymbols, concatFrames)
    }

    // ---- All grid sizes produce correct symbol counts ----

    @Test
    fun `all grid sizes produce correct symbol counts`() {
        val gridSizes = intArrayOf(40, 48, 56, 64, 72, 80, 96)
        for (gs in gridSizes) {
            val symbols = V7LabPrng.generateExpectedSymbols(gs, 42, 2)
            assertEquals("Grid $gs: symbol count", gs * gs, symbols.size)
        }
    }

    // ---- 96x96 data frame hashes ----

    @Test
    fun `96x96 v6_reference_4 data frame 0 hash`() {
        val symbols = V7LabPrng.generateExpectedSymbols(96, 42, 2)
        assertEquals(
            "8650ed9161e130e3951b59f556d64aefb8b51b2f675e070319c8d3c90530f8d0",
            sha256(symbols)
        )
    }

    @Test
    fun `96x96 candidate_8_a data frame 0 hash`() {
        val symbols = V7LabPrng.generateExpectedSymbols(96, 42, 3)
        assertEquals(
            "fea55f7531f0395f6b269d7cf6969140e2a459d505f57a44982501cb9557fa72",
            sha256(symbols)
        )
    }

    // ---- Reference seeds produce non-zero states ----

    @Test
    fun `all reference seeds are valid`() {
        for (seed in V7LabPrng.REFERENCE_SEEDS) {
            val prng = V7LabPrng(seed)
            assertNotNull(prng.next())
        }
    }

    // ---- Helpers ----

    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun concatAll(frames: List<ByteArray>): ByteArray {
        val totalSize = frames.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (f in frames) {
            System.arraycopy(f, 0, result, offset, f.size)
            offset += f.size
        }
        return result
    }
}
