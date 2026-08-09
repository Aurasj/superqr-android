package com.superqr.android.vision.v6

import com.superqr.android.vision.v6.classification.Xorshift32
import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.opencv.core.Core
import java.io.File

class V6StaticDetectorTest {

    init {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        } catch (e: Throwable) {
            println("OpenCV native library load warning: ${e.message}")
        }
    }

    private val isOpenCvAvailable: Boolean by lazy {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
            Core.getVersionString()
            true
        } catch (e: Throwable) {
            false
        }
    }

    @Test
    fun testEmptyImage() {
        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist", contractFile.exists())
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())

        val width = 1000
        val height = 1000
        val luma = ByteArray(width * height) { 255.toByte() } // all white

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "all-black", null)
        assertNotNull(result)
        assertFalse(result.borderFound) // No border should be found
    }

    @Test
    fun testSyntheticImageWithBorder() {
        Assume.assumeTrue("OpenCV native library is required for this test", isOpenCvAvailable)

        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist", contractFile.exists())
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())

        val width = 1000
        val height = 1000
        val luma = ByteArray(width * height) { 255.toByte() } // all white
        
        val stroke = 10
        for (y in 60..940) {
            for (x in 60..940) {
                if (x < 60 + stroke || x > 940 - stroke || y < 60 + stroke || y > 940 - stroke) {
                    luma[y * width + x] = 0.toByte()
                }
            }
        }

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "all-black", null)
        assertNotNull(result)
        assertTrue(result.borderFound)
    }

    @Test
    fun testSyntheticV6ContractDecoding() {
        Assume.assumeTrue("OpenCV native library is required for this test", isOpenCvAvailable)

        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist", contractFile.exists())
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())

        val width = 1000
        val height = 1000
        val luma = ByteArray(width * height) { 255.toByte() }
        
        fun drawRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
            for (y in y1..y2) {
                for (x in x1..x2) {
                    luma[y * width + x] = color.toByte()
                }
            }
        }

        // Draw Outer Border (60 to 940) with 20px thickness
        drawRect(60, 60, 940, 940, 0)
        drawRect(80, 80, 920, 920, 255) // Hollow inside

        fun drawAnchor(id: String, pattern: String) {
            val bbox = V6Contract.getAnchorBBox(id)
            val core = V6Contract.getAnchorCoreBBox(id)
            
            val ax1 = bbox.x1.toInt()
            val ay1 = bbox.y1.toInt()
            val ax2 = bbox.x2.toInt()
            val ay2 = bbox.y2.toInt()
            drawRect(ax1 + 2, ay1 + 2, ax2 - 2, ay2 - 2, 0)
            drawRect(ax1 + 8, ay1 + 8, ax2 - 8, ay2 - 8, 255)

            val cx = core.x1.toInt()
            val cy = core.y1.toInt()
            val cw = core.width.toInt()
            val ch = core.height.toInt()
            val hW = cw / 2
            val hH = ch / 2
            
            if (pattern[0] == '1') drawRect(cx, cy, cx + hW - 1, cy + hH - 1, 0) else drawRect(cx, cy, cx + hW - 1, cy + hH - 1, 255)
            if (pattern[1] == '1') drawRect(cx + hW, cy, cx + cw, cy + hH - 1, 0) else drawRect(cx + hW, cy, cx + cw, cy + hH - 1, 255)
            if (pattern[2] == '1') drawRect(cx + hW, cy + hH, cx + cw, cy + ch, 0) else drawRect(cx + hW, cy + hH, cx + cw, cy + ch, 255)
            if (pattern[3] == '1') drawRect(cx, cy + hH, cx + hW - 1, cy + ch, 0) else drawRect(cx, cy + hH, cx + hW - 1, cy + ch, 255)
        }

        drawAnchor("TL", "1000")
        drawAnchor("TR", "0100")
        drawAnchor("BR", "0010")
        drawAnchor("BL", "0001")

        // Draw Pilots
        val colorY = intArrayOf(0, 255, 170, 85)
        fun drawPilot(id: String, color: Int) {
            val core = V6Contract.getPilotCoreBBox(id)
            drawRect(core.x1.toInt(), core.y1.toInt(), core.x2.toInt() - 1, core.y2.toInt() - 1, color)
        }
        drawPilot("BLACK", colorY[0])
        drawPilot("WHITE", colorY[1])
        drawPilot("RED", colorY[2])
        drawPilot("BLUE", colorY[3])
        
        // Draw Cells using Xorshift32
        val prng = Xorshift32(42)
        val cols = V6Contract.getGridCols()
        val rows = V6Contract.getGridRows()
        val gridBBox = V6Contract.getGridBBox()
        val cellSize = V6Contract.getCellSize()
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val expectedIdx = (prng.nextInt() ushr 16) and 3
                val x1 = (gridBBox.x1 + c * cellSize).toInt()
                val y1 = (gridBBox.y1 + r * cellSize).toInt()
                val x2 = (gridBBox.x1 + (c + 1) * cellSize).toInt()
                val y2 = (gridBBox.y1 + (r + 1) * cellSize).toInt()
                drawRect(x1, y1, x2 - 1, y2 - 1, colorY[expectedIdx])
            }
        }

        val detector = V6StaticDetector()
        val result = detector.detect(luma, width, height, "deterministic random", null)
        
        assertTrue("Border should be found. Failure reason: ${result.failureReason}", result.borderFound)
        assertTrue("Orientation should be resolved. Failure reason: ${result.failureReason}", result.orientationResolved)
        assertEquals("1000", result.decodedCornerBits["TL"])
        assertEquals("0100", result.decodedCornerBits["TR"])
        assertEquals("0010", result.decodedCornerBits["BR"])
        assertEquals("0001", result.decodedCornerBits["BL"])
        
        assertEquals("All 400 cells should be correctly decoded", 400, result.colorCorrect)
        assertEquals("Expected CRC32 should be BEAFE8A7", 0xBEAFE8A7L, result.expectedCrc32)
        assertEquals("Decoded CRC32 should match expected exactly", 0xBEAFE8A7L, result.decodedCrc32)
    }

    @Test
    fun testGeometryOnlySkipsPayloadAndDiagnostics() {
        Assume.assumeTrue("OpenCV native library is required for this test", isOpenCvAvailable)
        val contractFile = File("src/main/assets/visual_contract.json")
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())
        val detector = V6StaticDetector()
        try {
            val result = detector.detectGeometry(buildDeterministicSyntheticV6Frame(), 1000, 1000)
            assertTrue(result.borderFound)
            assertTrue(result.orientationResolved)
            assertNotNull(result.finalInvHomography)
            assertEquals(0, result.colorTotal)
            assertNull(result.diagnosticPayload)
            assertNull(result.warpedLumaBytes)
            assertNull(result.transportFrame)
            assertEquals("FULL_DETECTION", result.geometrySource)
        } finally {
            detector.close()
        }
    }

    /**
     * Multi-frame tracking test.
     *
     * Feeds the same detector 4+ synthetic frames to exercise:
     *  - FULL_DETECTION acquisition (frames 1-3)
     *  - TRACKED_RESAMPLED proactive path (frame 4, small shift)
     *  - quantitative geometry lag check (no EMA trailing)
     *  - transport emission on TRACKED_RESAMPLED
     *  - fallback to FULL_DETECTION when tracking fails (frame 5, large shift)
     */
    @Test
    fun testMultiFrameTrackingAcquisitionAndResampledPath() {
        Assume.assumeTrue("OpenCV native library is required for this test", isOpenCvAvailable)

        val contractFile = File("src/main/assets/visual_contract.json")
        assertTrue("Contract file should exist", contractFile.exists())
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())

        val detector = V6StaticDetector()
        try {
            val width = 1000
            val height = 1000
            val baseLuma = buildDeterministicSyntheticV6Frame(width, height)

            fun shiftLuma(src: ByteArray, dx: Int, dy: Int): ByteArray {
                val dst = ByteArray(width * height) { 255.toByte() }
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val sx = x - dx
                        val sy = y - dy
                        if (sx in 0 until width && sy in 0 until height) {
                            dst[y * width + x] = src[sy * width + sx]
                        }
                    }
                }
                return dst
            }

            fun quadCenter(quad: List<DoubleArray>?): Pair<Double, Double> {
                require(quad != null && quad.size == 4)
                val cx = quad.map { it[0] }.average()
                val cy = quad.map { it[1] }.average()
                return cx to cy
            }

            // ── Frames 1-3: acquisition ──
            var frame3Center: Pair<Double, Double> = 0.0 to 0.0
            for (f in 1..3) {
                val result = detector.detect(baseLuma, width, height, "deterministic_random", null)
                assertTrue("Frame $f: border not found", result.borderFound)
                assertTrue("Frame $f: orientation not resolved", result.orientationResolved)
                assertEquals("Frame $f: source", "FULL_DETECTION",
                    result.diagnosticPayload?.classificationSource)
                assertEquals("Frame $f: correct symbols", 400, result.colorCorrect)
                assertEquals("Frame $f: CRC", 0xBEAFE8A7L, result.decodedCrc32)
                if (f == 3) frame3Center = quadCenter(result.detectedQuad)
            }

            // ── Frame 4: 5px shift → TRACKED_RESAMPLED, no EMA lag ──
            val shiftDx = 5
            val shiftDy = 3
            val luma4 = shiftLuma(baseLuma, shiftDx, shiftDy)
            val result4 = detector.detect(luma4, width, height, "deterministic_random", null)
            assertTrue("Frame 4: border not found", result4.borderFound)
            assertTrue("Frame 4: orientation not resolved", result4.orientationResolved)
            assertEquals("Frame 4: source", "TRACKED_RESAMPLED",
                result4.diagnosticPayload?.classificationSource)
            assertEquals("Frame 4: correct symbols", 400, result4.colorCorrect)
            assertEquals("Frame 4: CRC", 0xBEAFE8A7L, result4.decodedCrc32)
            assertNotNull("Frame 4: transport frame should be emitted", result4.transportFrame)
            assertTrue("Frame 4: transport should not be stale",
                result4.transportError != "HELD_TRACKING_NOT_FRESH")

            // Quantitative geometry lag check: with a controlled (5,3) translation,
            // the tracked quad center should move by approximately (5,3) pixels.
            // Under the old EMA (alpha=0.35) the steady-state displacement would be
            // only ~1.75 px per frame, so the first tracked frame would show ~1.75 px
            // movement instead of ~5 px.  After EMA removal, displacement should be
            // close to the actual shift (within ±2 px tolerance for OF noise).
            val frame4Center = quadCenter(result4.detectedQuad)
            val displacementDx = frame4Center.first - frame3Center.first
            val displacementDy = frame4Center.second - frame3Center.second
            assertEquals("Frame 4: quad X displacement should be ~${shiftDx} px (no EMA lag)",
                shiftDx.toDouble(), displacementDx, 2.5)
            assertEquals("Frame 4: quad Y displacement should be ~${shiftDy} px (no EMA lag)",
                shiftDy.toDouble(), displacementDy, 2.5)

            // ── Frame 5: severe 200px shift → tracking fails ──
            val luma5 = shiftLuma(baseLuma, 200, 150)
            val result5 = detector.detect(luma5, width, height, "deterministic_random", null)
            val src5 = result5.diagnosticPayload?.classificationSource ?: "NONE"
            assertTrue("Frame 5: source should be FULL_DETECTION (not $src5) at 200px displacement",
                src5 == "FULL_DETECTION")
        } finally {
            detector.close()
        }
    }

    companion object {
        /** Builds a synthetic 1000×1000 V6 deterministic_random frame (seed=42, CRC BEAFE8A7). */
        fun buildDeterministicSyntheticV6Frame(width: Int = 1000, height: Int = 1000): ByteArray {
            val luma = ByteArray(width * height) { 255.toByte() }

            fun drawRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
                for (y in y1..y2) {
                    for (x in x1..x2) {
                        luma[y * width + x] = color.toByte()
                    }
                }
            }

            // Outer border
            drawRect(60, 60, 940, 940, 0)
            drawRect(80, 80, 920, 920, 255)

            fun drawAnchor(id: String, pattern: String) {
                val bbox = V6Contract.getAnchorBBox(id)
                val core = V6Contract.getAnchorCoreBBox(id)
                val ax1 = bbox.x1.toInt(); val ay1 = bbox.y1.toInt()
                val ax2 = bbox.x2.toInt(); val ay2 = bbox.y2.toInt()
                drawRect(ax1 + 2, ay1 + 2, ax2 - 2, ay2 - 2, 0)
                drawRect(ax1 + 8, ay1 + 8, ax2 - 8, ay2 - 8, 255)
                val cx = core.x1.toInt(); val cy = core.y1.toInt()
                val cw = core.width.toInt(); val ch = core.height.toInt()
                val hW = cw / 2; val hH = ch / 2
                if (pattern[0] == '1') drawRect(cx, cy, cx + hW - 1, cy + hH - 1, 0) else drawRect(cx, cy, cx + hW - 1, cy + hH - 1, 255)
                if (pattern[1] == '1') drawRect(cx + hW, cy, cx + cw, cy + hH - 1, 0) else drawRect(cx + hW, cy, cx + cw, cy + hH - 1, 255)
                if (pattern[2] == '1') drawRect(cx + hW, cy + hH, cx + cw, cy + ch, 0) else drawRect(cx + hW, cy + hH, cx + cw, cy + ch, 255)
                if (pattern[3] == '1') drawRect(cx, cy + hH, cx + hW - 1, cy + ch, 0) else drawRect(cx, cy + hH, cx + hW - 1, cy + ch, 255)
            }
            drawAnchor("TL", "1000")
            drawAnchor("TR", "0100")
            drawAnchor("BR", "0010")
            drawAnchor("BL", "0001")

            // Pilots (luma-only approximation)
            val colorY = intArrayOf(0, 255, 170, 85)
            for ((i, name) in listOf("BLACK", "WHITE", "RED", "BLUE").withIndex()) {
                val core = V6Contract.getPilotCoreBBox(name)
                drawRect(core.x1.toInt(), core.y1.toInt(), core.x2.toInt() - 1, core.y2.toInt() - 1, colorY[i])
            }

            // Grid cells (deterministic_random, seed=42)
            val prng = Xorshift32(42)
            val cols = V6Contract.getGridCols()
            val rows = V6Contract.getGridRows()
            val gridBBox = V6Contract.getGridBBox()
            val cellSize = V6Contract.getCellSize()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val expectedIdx = (prng.nextInt() ushr 16) and 3
                    val x1 = (gridBBox.x1 + c * cellSize).toInt()
                    val y1 = (gridBBox.y1 + r * cellSize).toInt()
                    val x2 = (gridBBox.x1 + (c + 1) * cellSize).toInt()
                    val y2 = (gridBBox.y1 + (r + 1) * cellSize).toInt()
                    drawRect(x1, y1, x2 - 1, y2 - 1, colorY[expectedIdx])
                }
            }
            return luma
        }
    }
}
