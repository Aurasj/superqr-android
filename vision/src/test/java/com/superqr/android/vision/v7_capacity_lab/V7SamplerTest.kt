package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.*
import org.junit.Test

class V7SamplerTest {

    private val payloadBbox = doubleArrayOf(200.0, 200.0, 800.0, 800.0)

    @Test
    fun `projected centers for 40x40`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(40, payloadBbox)

        val cx = sampler.getCanonicalX()
        val cy = sampler.getCanonicalY()

        assertEquals(1600, cx.size)
        assertEquals(1600, cy.size)

        // First cell (row 0, col 0): center at (200 + 0.5*15, 200 + 0.5*15) = (207.5, 207.5)
        val cellSize = 600.0 / 40.0
        assertEquals(200.0 + 0.5 * cellSize, cx[0].toDouble(), 1e-6)
        assertEquals(200.0 + 0.5 * cellSize, cy[0].toDouble(), 1e-6)
    }

    @Test
    fun `projected centers for 96x96`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(96, payloadBbox)

        val cx = sampler.getCanonicalX()
        val cy = sampler.getCanonicalY()

        assertEquals(9216, cx.size)
        assertEquals(9216, cy.size)

        // First cell: cell size = 600/96 = 6.25
        val cellSize = 600.0 / 96.0
        assertEquals(200.0 + 0.5 * cellSize, cx[0].toDouble(), 1e-6)
        assertEquals(200.0 + 0.5 * cellSize, cy[0].toDouble(), 1e-6)

        // Last cell (row 95, col 95): cx = 200 + (95 + 0.5) * 6.25 = 200 + 95.5*6.25 = 796.875
        val lastIdx = 9215
        assertEquals(200.0 + 95.5 * cellSize, cx[lastIdx].toDouble(), 1e-6)
        assertEquals(200.0 + 95.5 * cellSize, cy[lastIdx].toDouble(), 1e-6)
    }

    @Test
    fun `row major ordering`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(40, payloadBbox)

        val cx = sampler.getCanonicalX()
        val cy = sampler.getCanonicalY()

        // Row 0, col 0 and row 0, col 1 should have same Y
        assertEquals(cy[0], cy[1])
        // Row 0, col 0 and row 1, col 0 should have same X but different Y
        assertEquals(cx[0], cx[40])
        assertTrue(cy[0] < cy[40])
    }

    @Test
    fun `grid size change rebuilds precomputed arrays`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(40, payloadBbox)
        assertEquals(40, sampler.gridSize)
        assertEquals(1600, sampler.totalCells)

        sampler.setGridSize(80, payloadBbox)
        assertEquals(80, sampler.gridSize)
        assertEquals(6400, sampler.totalCells)
        assertEquals(6400, sampler.getCanonicalX().size)
    }

    @Test
    fun `CENTER_1 sampling with identity homography`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(2, payloadBbox) // 2x2 = 4 cells

        // Identity homography: canonical → image at 1000x1000
        val hInv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        // Create a luma buffer
        val lumaBytes = ByteArray(1000 * 1000) { 128.toByte() }
        // Set known values at cell centers
        val cx = sampler.getCanonicalX()
        val cy = sampler.getCanonicalY()
        for (i in 0 until 4) {
            val px = cx[i].toInt()
            val py = cy[i].toInt()
            lumaBytes[py * 1000 + px] = (i * 50).toByte()
        }

        val validCount = sampler.sampleCenter1(hInv, lumaBytes, 1000, 1000, null)
        assertEquals(0, validCount) // no chroma reader

        val yArr = sampler.getYCenters()
        for (i in 0 until 4) {
            assertEquals("Cell $i", (i * 50).toInt() and 0xFF, yArr[i])
        }
    }

    @Test
    fun `CENTER_1 bounds handling`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(40, payloadBbox)

        // Homography that maps everything outside the image
        val hInv = doubleArrayOf(0.001, 0.0, -100.0, 0.0, 0.001, -100.0, 0.0, 0.0, 1.0)

        val lumaBytes = ByteArray(480 * 640) { 128.toByte() }
        val validCount = sampler.sampleCenter1(hInv, lumaBytes, 640, 480, null)
        // All should be out of bounds; Y defaults to 128
        val yArr = sampler.getYCenters()
        assertEquals(128, yArr[0])
    }

    @Test
    fun `median5 with known values`() {
        // median of {10, 20, 5, 60, 30} should be 20
        val med = V7HighDensitySampler.median5(10, 20, 5, 60, 30)
        assertEquals(20, med)
    }

    @Test
    fun `median5 already sorted`() {
        val med = V7HighDensitySampler.median5(1, 2, 3, 4, 5)
        assertEquals(3, med)
    }

    @Test
    fun `median5 reverse sorted`() {
        val med = V7HighDensitySampler.median5(5, 4, 3, 2, 1)
        assertEquals(3, med)
    }

    @Test
    fun `CROSS_5 produces valid samples`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(2, payloadBbox)

        val hInv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val lumaBytes = ByteArray(1000 * 1000) { 100.toByte() }

        val validCount = sampler.sampleCross5(hInv, lumaBytes, 1000, 1000, null)
        assertEquals(4, validCount)

        val yArr = sampler.getYCross5()
        // All 5 samples per cell read the same value (flat image), so median = 100
        for (i in 0 until 4) {
            assertEquals(100, yArr[i])
        }
    }
}
