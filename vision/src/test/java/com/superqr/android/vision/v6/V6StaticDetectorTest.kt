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
}
