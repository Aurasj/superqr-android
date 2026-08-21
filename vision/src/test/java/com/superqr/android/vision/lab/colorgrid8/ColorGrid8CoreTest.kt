package com.superqr.android.vision.lab.colorgrid8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGrid8CoreTest {
    @Test
    fun goldenFramesMatchProtocolAndDesktop() {
        val profile = ColorGrid8Profile(168, 144, 30)
        assertEquals(0x94A6DBA5L, ColorGrid8Codec.crc32(profile, 0))
        assertEquals(0xBADE60B7L, ColorGrid8Codec.crc32(profile, 1))
        assertEquals(0x276C602CL, ColorGrid8Codec.crc32(profile, 7))
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
        assertTrue(result.estimatedPostFecKibS > 201.27)
    }

    @Test
    fun targetProfileRetains200KibBudgetAfterHeaderPilotsAnd20PctFec() {
        val profile = ColorGrid8Profile(168, 144, 30)
        assertEquals(22901, profile.payloadCells)
        assertEquals(251.597900390625, profile.payloadKibS, 1e-9)
        assertEquals(201.2783203125, profile.postFecKibS(0.20), 1e-9)
        assertTrue(profile.postFecKibS(0.20) >= ColorGrid8Spec.TARGET_POST_FEC_KIB_S)
    }

    @Test
    fun headerIsRepeatedAcrossTwoRowsForWarpTolerance() {
        val profile = ColorGrid8Profile(168, 144, 30)
        val symbols = ColorGrid8Codec.buildSymbols(profile, 1234)
        for (col in 0 until profile.cols) {
            assertEquals(symbols[col], symbols[profile.cols + col])
        }
    }

    @Test
    fun detailedAnalyzerReportsHeaderFailureInsteadOfOpaqueNull() {
        val profile = ColorGrid8Profile(168, 144, 30)
        val flat = ByteArray(profile.totalCells) { 128.toByte() }
        val attempt = ColorGrid8Analyzer().analyzeDetailed(profile, ColorGrid8CellMeans(flat, flat, flat))
        assertEquals(ColorGrid8Stage.HEADER, attempt.stage)
        assertEquals(null, attempt.result)
        assertTrue(attempt.failure!!.contains("header"))
    }

    @Test
    fun detailedAnalyzerExposesDetectedAndExpectedProfileMismatch() {
        val encodedProfile = ColorGrid8Profile(168, 144, 30)
        val expectedProfile = ColorGrid8Profile(168, 144, 24)
        val symbols = ColorGrid8Codec.buildSymbols(encodedProfile, 9)
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
        val attempt = ColorGrid8Analyzer().analyzeDetailed(expectedProfile, ColorGrid8CellMeans(y, u, v))
        assertEquals(ColorGrid8Stage.PROFILE, attempt.stage)
        assertEquals(30, attempt.detectedHeader?.fps)
        assertTrue(attempt.failure!!.contains("expected"))
    }
}
