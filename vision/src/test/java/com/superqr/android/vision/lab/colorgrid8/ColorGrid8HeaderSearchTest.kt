package com.superqr.android.vision.lab.colorgrid8

import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8HeaderSearch as Search
import org.junit.Assert.*
import org.junit.Test

class ColorGrid8HeaderSearchTest {
    private val profile = ColorGrid8Profile(336, 288, 30, version = 2)

    private fun putHeader(atlas: ByteArray, candidate: Int, encoded: ColorGrid8Profile = profile,
                          firstFrame: Int = 7, secondFrame: Int = firstFrame, gap: Int = 160) {
        for (row in 0..1) {
            val symbols = ColorGrid8Codec.headerSymbols(encoded, if (row == 0) firstFrame else secondFrame)
            for (col in 0 until Search.WIDTH) {
                atlas[(candidate * 2 + row) * Search.WIDTH + col] =
                    (if (symbols[col].toInt() < 4) 40 else 40 + gap).toByte()
            }
        }
    }

    @Test fun failedAcquisitionNeverSelectsAPayloadSamplingCandidate() {
        val result = Search.select(profile, ByteArray(Search.WIDTH * Search.HEIGHT) { 128.toByte() }, ColorGrid8Analyzer())
        assertNull(result.selection)
        assertNull(result.bestProbe.header)
    }

    @Test fun searchIsBoundedAndIncludesAllOrientationsAndZeroOffset() {
        val candidates = (0 until Search.CANDIDATES).map(Search::candidate)
        assertEquals(100, candidates.toSet().size)
        for (rotation in 0..3) {
            assertEquals(25, candidates.count { it.rotation == rotation })
            assertTrue(candidates.any { it.rotation == rotation && it.dx == 0f && it.dy == 0f })
        }
        assertTrue(candidates.all { it.dx in -0.5f..0.5f && it.dy in -0.5f..0.5f })
        assertEquals(89_600, Search.READBACK_BYTES)
        assertTrue(Search.READBACK_BYTES < profile.totalCells * 4 * 4 / 17)
    }

    @Test fun selectsExactOrientationAndOffsetWithoutChangingHeaderBits() {
        val atlas = ByteArray(Search.WIDTH * Search.HEIGHT)
        val index = 2 * 25 + 3
        putHeader(atlas, index, firstFrame = 65535)
        val selection = Search.select(profile, atlas, ColorGrid8Analyzer()).selection!!
        assertEquals(Search.candidate(index), selection.candidate)
        assertEquals(65535, selection.probe.header!!.frameIndex)
        assertEquals(0, selection.probe.score)
    }

    @Test fun equallyClearHeadersPreferNoGeometricCorrection() {
        val atlas = ByteArray(Search.WIDTH * Search.HEIGHT)
        repeat(25) { putHeader(atlas, it) }
        val selection = Search.select(profile, atlas, ColorGrid8Analyzer()).selection!!
        assertEquals(Search.Candidate(0, 0f, 0f), selection.candidate)
    }

    @Test fun choosesCleanerBitsRatherThanFirstValidCandidate() {
        val atlas = ByteArray(Search.WIDTH * Search.HEIGHT)
        putHeader(atlas, 0, gap = 30)
        putHeader(atlas, 13, gap = 160)
        assertEquals(Search.candidate(13), Search.select(profile, atlas, ColorGrid8Analyzer()).selection!!.candidate)
    }

    @Test fun rejectsMixedFramesAndValidCrcForWrongProfileOrSeed() {
        val atlas = ByteArray(Search.WIDTH * Search.HEIGHT)
        putHeader(atlas, 0, firstFrame = 0, secondFrame = 1)
        putHeader(atlas, 1, encoded = profile.copy(fps = 60))
        putHeader(atlas, 2, encoded = profile.copy(seed = 1))
        putHeader(atlas, 3, encoded = ColorGrid8Profile(168, 144, 30, version = 1))
        assertNull(Search.select(profile, atlas, ColorGrid8Analyzer()).selection)
    }

    @Test fun rejectsCorruptHeaderInsteadOfRepairingBitsToAcquire() {
        val atlas = ByteArray(Search.WIDTH * Search.HEIGHT)
        putHeader(atlas, 12)
        // Change one encoded bit in both rows, leaving the CRC unchanged.
        for (row in 0..1) for (cell in 40..41) {
            val index = (12 * 2 + row) * Search.WIDTH + cell
            atlas[index] = if (atlas[index] == 40.toByte()) 200.toByte() else 40
        }
        assertNull(Search.select(profile, atlas, ColorGrid8Analyzer()).selection)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongAtlasDimensions() {
        Search.select(profile, ByteArray(Search.WIDTH * Search.HEIGHT - 1), ColorGrid8Analyzer())
    }
}
