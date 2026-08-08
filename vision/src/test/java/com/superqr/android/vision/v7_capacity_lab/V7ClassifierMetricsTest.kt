package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.*
import org.junit.Test

class V7ClassifierMetricsTest {

    // ---- Classifier tests ----

    @Test
    fun `exact center produces correct symbol`() {
        val classifier = V7SoftClassifier()
        // Set up 4-color centers
        val centers = arrayOf(
            intArrayOf(16, 128, 128),   // BLACK
            intArrayOf(235, 128, 128),  // WHITE
            intArrayOf(82, 90, 240),    // RED
            intArrayOf(41, 240, 110)    // BLUE
        )
        classifier.setCenters(centers)
        classifier.setCellCount(1)

        // Sample exactly at BLACK center
        classifier.classify(
            intArrayOf(16), intArrayOf(128), intArrayOf(128), 1
        )
        assertEquals(0, classifier.bestSymbols[0].toInt())
        assertEquals(0, classifier.bestDistances[0])
    }

    @Test
    fun `ambiguous sample produces erasure`() {
        val classifier = V7SoftClassifier()
        val centers = arrayOf(
            intArrayOf(16, 128, 128),
            intArrayOf(235, 128, 128),
            intArrayOf(82, 90, 240),
            intArrayOf(41, 240, 110)
        )
        classifier.setCenters(centers)
        classifier.marginThreshold = 0.9
        classifier.maxDistanceThreshold = 40000
        classifier.setCellCount(1)

        // Sample exactly midway between BLACK and WHITE in Y
        val midY = (16 + 235) / 2
        classifier.classify(
            intArrayOf(midY), intArrayOf(128), intArrayOf(128), 1
        )
        // Should be erased because distance to BLACK and WHITE are equal
        assertEquals(V7SoftClassifier.ERASURE_MARKER, classifier.bestSymbols[0])
    }

    @Test
    fun `far sample produces erasure`() {
        val classifier = V7SoftClassifier()
        val centers = arrayOf(
            intArrayOf(16, 128, 128),
            intArrayOf(235, 128, 128)
        )
        classifier.setCenters(centers)
        classifier.maxDistanceThreshold = 10000
        classifier.marginThreshold = 0.9
        classifier.setCellCount(1)

        // Very far from all centers
        classifier.classify(
            intArrayOf(0), intArrayOf(0), intArrayOf(0), 1
        )
        assertEquals(V7SoftClassifier.ERASURE_MARKER, classifier.bestSymbols[0])
    }

    @Test
    fun `best and second best ordering`() {
        val classifier = V7SoftClassifier()
        val centers = arrayOf(
            intArrayOf(16, 128, 128),
            intArrayOf(235, 128, 128)
        )
        classifier.setCenters(centers)
        classifier.setCellCount(1)

        // Closer to WHITE
        classifier.classify(
            intArrayOf(200), intArrayOf(128), intArrayOf(128), 1
        )
        assertEquals(1, classifier.bestSymbols[0].toInt())  // WHITE
        assertEquals(0, classifier.secondBestSymbols[0].toInt())  // BLACK
    }

    @Test
    fun `erasure marker never coerced to symbol 0`() {
        val classifier = V7SoftClassifier()
        val centers = arrayOf(
            intArrayOf(16, 128, 128),
            intArrayOf(235, 128, 128)
        )
        classifier.setCenters(centers)
        classifier.maxDistanceThreshold = 100
        classifier.marginThreshold = 0.5
        classifier.setCellCount(3)

        val yArr = intArrayOf(0, 127, 255)
        val uArr = intArrayOf(0, 0, 0)
        val vArr = intArrayOf(0, 0, 0)

        classifier.classify(yArr, uArr, vArr, 3)
        // First cell is far from both — must be erased, not symbol 0
        assertEquals(V7SoftClassifier.ERASURE_MARKER, classifier.bestSymbols[0])
    }

    // ---- Metrics tests ----

    @Test
    fun `perfect frame produces zero error`() {
        val metrics = V7ChannelMetrics()
        val totalCells = 1600 // 40x40
        val expected = ByteArray(totalCells) { 0.toByte() }
        val decoded = ByteArray(totalCells) { 0.toByte() }

        val result = metrics.computeFrame(expected, decoded, 2, 4, totalCells)
        assertEquals(0.0, result.serAll, 1e-9)
        assertEquals(0.0, result.conditionalSer, 1e-9)
        assertEquals(0.0, result.erasureRate, 1e-9)
        assertEquals(0.0, result.berAccepted, 1e-9)
        assertEquals(totalCells, result.correctSymbols)
        assertEquals(0, result.wrongSymbols)
        assertEquals(0, result.erasures)
    }

    @Test
    fun `wrong accepted symbol counted correctly`() {
        val metrics = V7ChannelMetrics()
        val totalCells = 100
        // expected: all symbol 1 (WHITE, 0b01), decoded: all symbol 2 (RED, 0b10)
        val expected = ByteArray(totalCells) { 1.toByte() }
        val decoded = ByteArray(totalCells) { 2.toByte() }
        // For 2 bits/cell, Hamming distance between 01 (1) and 10 (2) = 2

        val result = metrics.computeFrame(expected, decoded, 2, 4, totalCells)
        assertEquals(100.0 / 100.0, result.serAll, 1e-9)      // all wrong
        assertEquals(100.0 / 100.0, result.conditionalSer, 1e-9) // all accepted are wrong
        assertEquals(0.0, result.erasureRate, 1e-9)
        // BER: 2 hamming errors per cell * 100 cells / (100 * 2) = 200/200 = 1.0
        assertEquals(1.0, result.berAccepted, 1e-9)
    }

    @Test
    fun `erasure rate computed correctly`() {
        val metrics = V7ChannelMetrics()
        val totalCells = 100
        val expected = ByteArray(totalCells) { 0.toByte() }
        val decoded = ByteArray(totalCells)
        // 30 erased, 70 correct
        for (i in 0 until 30) decoded[i] = V7ChannelMetrics.ERASURE_MARKER
        for (i in 30 until 100) decoded[i] = 0

        val result = metrics.computeFrame(expected, decoded, 2, 4, totalCells)
        assertEquals(0.3, result.erasureRate, 1e-9)
        assertEquals(30.0 / 100.0, result.serAll, 1e-9)       // 30 erasures = 30 errors
        assertEquals(0.0, result.conditionalSer, 1e-9)          // no wrong acceptances
        assertEquals(0.0, result.berAccepted, 1e-9)
    }

    @Test
    fun `SER_ALL includes erasures as errors`() {
        val metrics = V7ChannelMetrics()
        val totalCells = 100
        val expected = ByteArray(totalCells) { 1.toByte() }
        val decoded = ByteArray(totalCells)
        // 10 erased, 10 wrong (decoded=2), 80 correct (decoded=1)
        for (i in 0 until 10) decoded[i] = V7ChannelMetrics.ERASURE_MARKER
        for (i in 10 until 20) decoded[i] = 2
        for (i in 20 until 100) decoded[i] = 1

        val result = metrics.computeFrame(expected, decoded, 2, 4, totalCells)
        // SER_ALL: (10 wrong + 10 erasures) / 100 = 0.20
        assertEquals(0.20, result.serAll, 1e-9)
        // CONDITIONAL_SER: 10 wrong / 90 non-erased = 0.111...
        assertEquals(10.0 / 90.0, result.conditionalSer, 1e-7)
        assertEquals(0.10, result.erasureRate, 1e-9)
    }

    @Test
    fun `BER_ACCEPTED Hamming semantics`() {
        val metrics = V7ChannelMetrics()
        val totalCells = 10
        val expected = ByteArray(totalCells) { 3.toByte() } // 0b11
        val decoded = ByteArray(totalCells) { 0.toByte() }  // 0b00
        // Hamming distance 0b11 vs 0b00 = 2 per cell

        val result = metrics.computeFrame(expected, decoded, 2, 4, totalCells)
        // BER: 2 hamming errors * 10 cells / (10 * 2 bits) = 20/20 = 1.0
        assertEquals(1.0, result.berAccepted, 1e-9)
    }

    @Test
    fun `all erased frame returns NaN for conditional metrics`() {
        val metrics = V7ChannelMetrics()
        val totalCells = 100
        val expected = ByteArray(totalCells) { 0.toByte() }
        val decoded = ByteArray(totalCells) { V7ChannelMetrics.ERASURE_MARKER }

        val result = metrics.computeFrame(expected, decoded, 2, 4, totalCells)
        assertEquals(1.0, result.serAll, 1e-9)
        assertEquals(1.0, result.erasureRate, 1e-9)
        assertTrue(result.conditionalSer.isNaN())
        assertTrue(result.berAccepted.isNaN())
    }

    // ---- Confidence margin tests ----

    @Test
    fun `confidence margin computed correctly`() {
        val classifier = V7SoftClassifier()
        val centers = arrayOf(
            intArrayOf(16, 128, 128),
            intArrayOf(235, 128, 128)
        )
        classifier.setCenters(centers)
        classifier.setCellCount(1)

        // Closer to BLACK
        classifier.classify(
            intArrayOf(50), intArrayOf(128), intArrayOf(128), 1
        )
        val margin = classifier.confidenceMargin(0)
        assertTrue(margin > 0.0)
        assertTrue(margin < 1.0)  // Not ambiguous
    }
}
