package com.superqr.android.vision.v7_capacity_lab.colorgrid8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8CoreTest {
    @Test
    fun goldenFramesMatchProtocolAndDesktop() {
        val profile = ColorGrid8Profile(168, 144, 30)
        assertEquals(0xCE0A026FL, ColorGrid8Codec.crc32(profile, 0))
        assertEquals(0x6B848359L, ColorGrid8Codec.crc32(profile, 1))
        assertEquals(0xF7AA5BCFL, ColorGrid8Codec.crc32(profile, 7))
    }

    @Test
    fun idealPilotCalibratedChannelDecodesWithoutErrors() {
        val profile = ColorGrid8Profile(168, 144, 30)
        val symbols = ColorGrid8Codec.buildSymbols(profile, 7)
        val yCentres = intArrayOf(60, 62, 58, 64, 190, 192, 188, 194)
        val uCentres = intArrayOf(80, 80, 176, 176, 80, 80, 176, 176)
        val vCentres = intArrayOf(80, 176, 80, 176, 80, 176, 80, 176)
        val y = ByteArray(symbols.size)
        val u = ByteArray(symbols.size)
        val v = ByteArray(symbols.size)
        for (index in symbols.indices) {
            val symbol = symbols[index].toInt() and 7
            y[index] = yCentres[symbol].toByte()
            u[index] = uCentres[symbol].toByte()
            v[index] = vCentres[symbol].toByte()
        }

        val result = ColorGrid8Analyzer().analyze(profile, ColorGrid8CellMeans(y, u, v))
        assertNotNull(result)
        result!!
        assertEquals(7, result.header.frameIndex)
        assertEquals(0, result.symbolErrors)
        assertEquals(0, result.bitErrors)
        assertEquals(0, result.erasures)
        assertEquals(0.0, result.symbolErrorRate, 0.0)
        assertEquals(0.0, result.bitErrorRate, 0.0)
        assertTrue(result.pilotMinUvDistance >= 90.0)
        assertTrue(result.estimatedPostFecKibS > 202.70)
    }

    @Test
    fun targetProfileRetains200KibBudgetAfterHeaderPilotsAnd20PctFec() {
        val profile = ColorGrid8Profile(168, 144, 30)
        assertEquals(23063, profile.payloadCells)
        assertEquals(253.377685546875, profile.payloadKibS, 1e-9)
        assertEquals(202.7021484375, profile.postFecKibS(0.20), 1e-9)
        assertTrue(profile.postFecKibS(0.20) >= ColorGrid8Spec.TARGET_POST_FEC_KIB_S)
    }
}
