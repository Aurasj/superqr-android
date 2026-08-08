package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Receiver-level integration tests for V7CapacityLabReceiver.
 *
 * Validates synthetic flows: perfect frame, wrong symbol, erasure, all-erased,
 * calibration-not-ready, sampler mode selection, expected frame change/reset.
 */
class V7ReceiverTest {

    companion object {
        private lateinit var manifest: V7LabManifest

        @BeforeClass
        @JvmStatic
        fun loadManifest() {
            // Try multiple locations for the manifest
            val paths = listOf(
                "src/test/resources/v7_capacity_lab/lab_manifest.json",
                "../app/src/main/assets/v7_capacity_lab/lab_manifest.json",
                "../../superqr-protocol/test-vectors/v7-capacity-lab/lab_manifest.json"
            )
            val file = paths.map { File(it) }.firstOrNull { it.exists() }
                ?: throw RuntimeException("Cannot find lab_manifest.json")
            manifest = V7LabManifest.loadFromBytes(file.readBytes())
        }
    }

    // ---- Profile selection ----

    @Test
    fun `select profile changes grid and palette`() {
        val receiver = V7CapacityLabReceiver(manifest)
        assertTrue(receiver.selectProfile("ref_48x48_candidate_8_a_seed42"))
        assertEquals(48, receiver.gridSize)
        assertEquals("candidate_8_a", receiver.paletteName)
        assertEquals(3, receiver.bitsPerCell)
        assertEquals(8, receiver.paletteSize)
        assertEquals(0, receiver.expectedDataFrameIndex) // reset
        receiver.close()
    }

    @Test
    fun `select unknown profile returns false`() {
        val receiver = V7CapacityLabReceiver(manifest)
        assertFalse(receiver.selectProfile("nonexistent"))
        receiver.close()
    }

    // ---- Expected frame management ----

    @Test
    fun `expected frame advance and retreat`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")

        assertEquals(0, receiver.expectedDataFrameIndex)
        assertTrue(receiver.advanceExpectedFrame())
        assertEquals(1, receiver.expectedDataFrameIndex)

        assertTrue(receiver.retreatExpectedFrame())
        assertEquals(0, receiver.expectedDataFrameIndex)

        // Cannot retreat past 0
        assertFalse(receiver.retreatExpectedFrame())
        assertEquals(0, receiver.expectedDataFrameIndex)

        // Advance to last
        receiver.expectedDataFrameIndex = 3
        assertFalse(receiver.advanceExpectedFrame()) // at end

        receiver.resetExpectedFrame()
        assertEquals(0, receiver.expectedDataFrameIndex)
        receiver.close()
    }

    @Test
    fun `get expected symbols returns valid data`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")

        val symbols = receiver.getExpectedSymbols()
        assertNotNull(symbols)
        assertEquals(1600, symbols!!.size)
        // First four symbols per V6 golden
        assertEquals(1, symbols[0].toInt() and 0xFF)
        assertEquals(2, symbols[1].toInt() and 0xFF)
        assertEquals(3, symbols[2].toInt() and 0xFF)
        assertEquals(0, symbols[3].toInt() and 0xFF)
        receiver.close()
    }

    // ---- Sampler mode selection ----

    @Test
    fun `sampler mode selection`() {
        val receiver = V7CapacityLabReceiver(manifest)
        assertEquals(V7HighDensitySampler.ProbeMode.CENTER_1, receiver.probeMode)

        receiver.probeMode = V7HighDensitySampler.ProbeMode.CROSS_5
        assertEquals(V7HighDensitySampler.ProbeMode.CROSS_5, receiver.probeMode)

        receiver.probeMode = V7HighDensitySampler.ProbeMode.CENTER_1
        assertEquals(V7HighDensitySampler.ProbeMode.CENTER_1, receiver.probeMode)
        receiver.close()
    }

    // ---- Calibration ----

    @Test
    fun `calibration status reflects state`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")
        // Initially: 0/4 calibrated
        assertEquals("0/4", receiver.lastResult?.calibrationStatus ?: "0/4")

        // Simulate pilot calibration
        receiver.calibrator.setPilotCenter(0, 16, 128, 128)
        assertEquals(1, receiver.calibrator.calibratedCount())
        receiver.close()
    }

    @Test
    fun `reset calibration clears all`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")
        receiver.calibrator.setPilotCenter(0, 16, 128, 128)
        receiver.calibrator.setPilotCenter(1, 235, 128, 128)
        assertEquals(2, receiver.calibrator.calibratedCount())

        receiver.resetCalibration()
        assertEquals(0, receiver.calibrator.calibratedCount())
        receiver.close()
    }

    // ---- Receiver has expected components ----

    @Test
    fun `receiver is created with sampler calibrator and classifier`() {
        val receiver = V7CapacityLabReceiver(manifest)
        assertNotNull(receiver.sampler)
        assertNotNull(receiver.calibrator)
        assertNotNull(receiver.classifier)
        assertNotNull(receiver.metrics)
        receiver.close()
    }

    // ---- Synthetic classification through receiver components ----

    @Test
    fun `perfect expected symbols produce zero error`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")

        // Manually calibrate via V6-like pilot centers
        receiver.calibrator.setPilotCenter(0, 16, 128, 128)   // BLACK
        receiver.calibrator.setPilotCenter(1, 235, 128, 128)  // WHITE
        receiver.calibrator.setPilotCenter(2, 82, 90, 240)    // RED
        receiver.calibrator.setPilotCenter(3, 41, 240, 110)   // BLUE

        // Generate expected symbols and fabricate "perfect" decoded symbols
        val expected = receiver.getExpectedSymbols()!!
        val decoded = expected.copyOf()

        val metrics = receiver.metrics.computeFrame(
            expectedSymbols = expected,
            decodedSymbols = decoded,
            bitsPerCell = 2,
            paletteSize = 4,
            totalCells = receiver.gridSize * receiver.gridSize
        )

        assertEquals(0.0, metrics.serAll, 1e-9)
        assertEquals(0.0, metrics.conditionalSer, 1e-9)
        assertEquals(0.0, metrics.erasureRate, 1e-9)
        assertEquals(0.0, metrics.berAccepted, 1e-9)
        assertEquals(receiver.gridSize * receiver.gridSize, metrics.correctSymbols)
        receiver.close()
    }

    @Test
    fun `one wrong accepted symbol`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")

        val totalCells = receiver.gridSize * receiver.gridSize
        val expected = ByteArray(totalCells) { 0 }
        val decoded = ByteArray(totalCells) { 0 }
        // One wrong: expected 0, decoded 1
        decoded[0] = 1

        val metrics = receiver.metrics.computeFrame(
            expectedSymbols = expected,
            decodedSymbols = decoded,
            bitsPerCell = 2,
            paletteSize = 4,
            totalCells = totalCells
        )

        assertEquals(1.0 / totalCells, metrics.serAll, 1e-9)
        assertEquals(1.0 / totalCells, metrics.conditionalSer, 1e-9)
        assertEquals(0.0, metrics.erasureRate, 1e-9)
        assertEquals(1, metrics.wrongSymbols)
        receiver.close()
    }

    @Test
    fun `one erasure`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")

        val totalCells = receiver.gridSize * receiver.gridSize
        val expected = ByteArray(totalCells) { 0 }
        val decoded = ByteArray(totalCells) { 0 }
        decoded[0] = V7SoftClassifier.ERASURE_MARKER

        val metrics = receiver.metrics.computeFrame(
            expectedSymbols = expected,
            decodedSymbols = decoded,
            bitsPerCell = 2,
            paletteSize = 4,
            totalCells = totalCells
        )

        assertEquals(1.0 / totalCells, metrics.serAll, 1e-9)
        assertEquals(1, metrics.erasures)
        // 1 erasure, 0 wrong, 1599 non-erased: conditionalSer = 0/1599 = 0.0
        assertEquals(0.0, metrics.conditionalSer, 1e-9)
        assertEquals(0.0, metrics.berAccepted, 1e-9)
        receiver.close()
    }

    @Test
    fun `all erased result`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")

        val totalCells = receiver.gridSize * receiver.gridSize
        val expected = ByteArray(totalCells) { 0 }
        val decoded = ByteArray(totalCells) { V7SoftClassifier.ERASURE_MARKER }

        val metrics = receiver.metrics.computeFrame(
            expectedSymbols = expected,
            decodedSymbols = decoded,
            bitsPerCell = 2,
            paletteSize = 4,
            totalCells = totalCells
        )

        assertEquals(1.0, metrics.serAll, 1e-9)
        assertEquals(1.0, metrics.erasureRate, 1e-9)
        assertTrue(metrics.conditionalSer.isNaN())
        assertTrue(metrics.berAccepted.isNaN())
        assertEquals(0, metrics.correctSymbols)
        assertEquals(0, metrics.wrongSymbols)
        assertEquals(totalCells, metrics.erasures)
        receiver.close()
    }

    @Test
    fun `calibration not ready — classifier still operates`() {
        val receiver = V7CapacityLabReceiver(manifest)
        receiver.selectProfile("ref_40x40_v6_reference_4_seed42")
        receiver.resetCalibration()
        assertEquals(0, receiver.calibrator.calibratedCount())

        // Classifier should still produce output even with no calibration
        // (all distances to default centers [128,128,128])
        receiver.classifier.setCenters(receiver.calibrator.getCentersSnapshot())
        val yArr = IntArray(receiver.gridSize * receiver.gridSize) { 128 }
        val uArr = IntArray(receiver.gridSize * receiver.gridSize) { 128 }
        val vArr = IntArray(receiver.gridSize * receiver.gridSize) { 128 }
        receiver.classifier.classify(yArr, uArr, vArr, receiver.gridSize * receiver.gridSize)
        // Result should all be symbol 0 (all centers are [128,128,128], all tied)
        assertEquals(0, receiver.classifier.bestSymbols[0].toInt())
        receiver.close()
    }

    // ---- Timing ----

    @Test
    fun `timing struct is initialized and mutable`() {
        val receiver = V7CapacityLabReceiver(manifest)
        // Timing fields start at 0
        assertEquals(0, receiver.timing.carrierGeometryUs)
        assertEquals(0, receiver.timing.totalAnalysisUs)
        // Can be written
        receiver.timing.carrierGeometryUs = 5000
        assertEquals(5000, receiver.timing.carrierGeometryUs)
        receiver.close()
    }
}
