package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.*
import org.junit.Test

class V7SamplerTest {

    private val payloadBbox = doubleArrayOf(200.0, 200.0, 800.0, 800.0)

    @Test
    fun `projected centers for 40x40`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(40, payloadBbox)
        val cx = sampler.getCanonicalX(); val cy = sampler.getCanonicalY()
        assertEquals(1600, cx.size); assertEquals(1600, cy.size)
        val cellSize = 600.0 / 40.0
        assertEquals(200.0 + 0.5 * cellSize, cx[0].toDouble(), 1e-6)
        assertEquals(200.0 + 0.5 * cellSize, cy[0].toDouble(), 1e-6)
    }

    @Test
    fun `projected centers for rectangular production bbox`() {
        val sampler = V7HighDensitySampler()
        val bbox = doubleArrayOf(100.0, 190.0, 900.0, 810.0)
        sampler.setGridSize(48, bbox)
        val cx = sampler.getCanonicalX(); val cy = sampler.getCanonicalY()
        val cellW = 800.0 / 48.0
        val cellH = 620.0 / 48.0
        assertEquals(cellW, sampler.getCellWidth().toDouble(), 1e-4)
        assertEquals(cellH, sampler.getCellHeight().toDouble(), 1e-4)
        assertEquals(100.0 + 0.5 * cellW, cx[0].toDouble(), 1e-4)
        assertEquals(190.0 + 0.5 * cellH, cy[0].toDouble(), 1e-4)
        val last = 48 * 48 - 1
        assertEquals(100.0 + 47.5 * cellW, cx[last].toDouble(), 1e-4)
        assertEquals(190.0 + 47.5 * cellH, cy[last].toDouble(), 1e-4)
        assertTrue("production cells must be rectangular", sampler.getCellWidth() > sampler.getCellHeight())
    }

    @Test
    fun `projected centers for 96x96`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridSize(96, payloadBbox)
        val cx = sampler.getCanonicalX(); val cy = sampler.getCanonicalY()
        assertEquals(9216, cx.size); assertEquals(9216, cy.size)
        val cellSize = 600.0 / 96.0
        assertEquals(200.0 + 0.5 * cellSize, cx[0].toDouble(), 1e-6)
        assertEquals(200.0 + 0.5 * cellSize, cy[0].toDouble(), 1e-6)
        val lastIdx = 9215
        assertEquals(200.0 + 95.5 * cellSize, cx[lastIdx].toDouble(), 1e-6)
        assertEquals(200.0 + 95.5 * cellSize, cy[lastIdx].toDouble(), 1e-6)
    }

    @Test
    fun `row major ordering`() {
        val sampler = V7HighDensitySampler(); sampler.setGridSize(40, payloadBbox)
        val cx = sampler.getCanonicalX(); val cy = sampler.getCanonicalY()
        assertEquals(cy[0], cy[1]); assertEquals(cx[0], cx[40]); assertTrue(cy[0] < cy[40])
    }

    @Test
    fun `grid size change rebuilds precomputed arrays`() {
        val sampler = V7HighDensitySampler(); sampler.setGridSize(40, payloadBbox)
        assertEquals(40, sampler.gridSize); assertEquals(1600, sampler.totalCells)
        sampler.setGridSize(80, payloadBbox)
        assertEquals(80, sampler.gridSize); assertEquals(6400, sampler.totalCells); assertEquals(6400, sampler.getCanonicalX().size)
    }

    @Test
    fun `rectangular grid computes independent pitch`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridShape(50, 64, doubleArrayOf(100.0, 190.0, 900.0, 810.0))
        assertEquals(50, sampler.gridRows)
        assertEquals(64, sampler.gridCols)
        assertEquals(3200, sampler.totalCells)
        assertEquals(12.5, sampler.getCellWidth().toDouble(), 1e-4)
        assertEquals(12.4, sampler.getCellHeight().toDouble(), 1e-4)
    }

    @Test
    fun `luma patch 9 uses median and no chroma`() {
        val sampler = V7HighDensitySampler()
        sampler.setGridShape(2, 2, doubleArrayOf(0.0, 0.0, 20.0, 20.0))
        val luma = ByteArray(20 * 20) { 40 }
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(4, sampler.sampleLumaPatch9(identity, luma, 20, 20))
        assertTrue(sampler.getYLumaPatch9().all { it == 40 })
        assertTrue(sampler.getValidMask().all { it.toInt() == 1 })
    }

    @Test
    fun `CENTER_1 sampling with identity homography`() {
        val sampler = V7HighDensitySampler(); sampler.setGridSize(2, payloadBbox)
        val hInv = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val lumaBytes = ByteArray(1000 * 1000) { 128.toByte() }
        val cx = sampler.getCanonicalX(); val cy = sampler.getCanonicalY()
        for (i in 0 until 4) lumaBytes[cy[i].toInt() * 1000 + cx[i].toInt()] = (i * 50).toByte()
        val validCount = sampler.sampleCenter1(hInv, lumaBytes, 1000, 1000, null)
        assertEquals(4, validCount)
        val yArr = sampler.getYCenters()
        for (i in 0 until 4) assertEquals("Cell $i", (i * 50).toInt() and 0xFF, yArr[i])
        assertTrue(sampler.getProbeValidityMask().all { (it.toInt() and 0xFF) == 0x01 })
    }

    @Test
    fun `CENTER_1 bounds handling`() {
        val sampler = V7HighDensitySampler(); sampler.setGridSize(40, payloadBbox)
        val hInv = doubleArrayOf(0.001,0.0,-100.0,0.0,0.001,-100.0,0.0,0.0,1.0)
        val lumaBytes = ByteArray(480 * 640) { 128.toByte() }
        val validCount = sampler.sampleCenter1(hInv, lumaBytes, 640, 480, null)
        assertEquals(0, validCount); assertEquals(128, sampler.getYCenters()[0])
        assertTrue(sampler.getProbeValidityMask().all { (it.toInt() and 0xFF) == 0 })
    }

    @Test fun `median5 with known values`() { assertEquals(20, V7HighDensitySampler.median5(10,20,5,60,30)) }
    @Test fun `median5 already sorted`() { assertEquals(3, V7HighDensitySampler.median5(1,2,3,4,5)) }
    @Test fun `median5 reverse sorted`() { assertEquals(3, V7HighDensitySampler.median5(5,4,3,2,1)) }

    @Test
    fun `CROSS_5 produces valid samples and five probe mask`() {
        val sampler = V7HighDensitySampler(); sampler.setGridSize(2, payloadBbox)
        val hInv = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val lumaBytes = ByteArray(1000 * 1000) { 100.toByte() }
        val validCount = sampler.sampleCross5(hInv, lumaBytes, 1000, 1000, null)
        assertEquals(4, validCount)
        for (i in 0 until 4) assertEquals(100, sampler.getYCross5()[i])
        assertTrue(sampler.getValidMask().all { it.toInt() == 1 })
        assertTrue(sampler.getProbeValidityMask().all { (it.toInt() and 0xFF) == 0b11111 })
    }
}
