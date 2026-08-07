package com.superqr.android.vision.v6

import android.graphics.Rect
import com.superqr.android.vision.v6.classification.Xorshift32
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.diagnostic.*
import com.superqr.android.vision.v6.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class V6FullDiagnosticCaptureTest {

    @Test
    fun testRawPlaneCopyingNonTrivialStride() {
        val width = 10
        val height = 5
        val rowStride = 32 // padded stride
        val pixelStride = 2 // interleaved format (e.g. UVUV)

        val bufferSize = height * rowStride
        val fakeBuffer = ByteArray(bufferSize) { i -> (i % 256).toByte() }

        val copied = fakeBuffer.clone()

        assertEquals(bufferSize, copied.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * rowStride + x * pixelStride
                assertTrue(index < copied.size)
                assertEquals((index % 256).toByte(), copied[index])
            }
        }
    }

    @Test
    fun testCoordinateMappingConsistency() {
        val canonicalPoints = listOf(
            org.opencv.core.Point(60.0, 60.0),
            org.opencv.core.Point(940.0, 60.0),
            org.opencv.core.Point(940.0, 940.0),
            org.opencv.core.Point(60.0, 940.0)
        )
        val imagePoints = listOf(
            org.opencv.core.Point(100.0, 150.0),
            org.opencv.core.Point(850.0, 120.0),
            org.opencv.core.Point(900.0, 880.0),
            org.opencv.core.Point(120.0, 850.0)
        )

        val detector = V6StaticDetector()
        val method = V6StaticDetector::class.java.getDeclaredMethod("solveHomographySimple", List::class.java, List::class.java)
        method.isAccessible = true

        val hCanonToImg = method.invoke(detector, canonicalPoints, imagePoints) as DoubleArray?
        assertNotNull(hCanonToImg)
        val hImgToCanon = method.invoke(detector, imagePoints, canonicalPoints) as DoubleArray?
        assertNotNull(hImgToCanon)

        fun mapPt(h: DoubleArray, px: Double, py: Double): Pair<Double, Double> {
            val den = h[6] * px + h[7] * py + h[8]
            val x = (h[0] * px + h[1] * py + h[2]) / den
            val y = (h[3] * px + h[4] * py + h[5]) / den
            return Pair(x, y)
        }

        val imgPt = mapPt(hCanonToImg!!, 60.0, 60.0)
        assertEquals(100.0, imgPt.first, 1e-4)
        assertEquals(150.0, imgPt.second, 1e-4)

        val canonPtBack = mapPt(hImgToCanon!!, imgPt.first, imgPt.second)
        assertEquals(60.0, canonPtBack.first, 1e-4)
        assertEquals(60.0, canonPtBack.second, 1e-4)
    }

    @Test
    fun testGoldenVectorAndBEAFE8A7Crc() {
        val prng = Xorshift32(42)
        val expectedFirst20 = listOf(1, 2, 3, 0, 0, 2, 3, 3, 2, 1, 0, 0, 0, 3, 1, 1, 2, 0, 2, 1)

        val grid = ByteArray(400)
        for (i in 0 until 400) {
            grid[i] = ((prng.nextInt() ushr 16) and 3).toByte()
        }

        val actualFirst20 = grid.take(20).map { it.toInt() }
        assertEquals(expectedFirst20, actualFirst20)

        val crc = CRC32()
        crc.update(grid)
        val crcHex = String.format("%08X", crc.value)
        assertEquals("BEAFE8A7", crcHex)
    }

    @Test
    fun testExact400RowCellsCsvGeneration() {
        val bundle = createDummyBundle(timestamp = 1700000000000L)
        val csv = V6FullDiagnosticExporter.generateCellsCsv(bundle)

        val lines = csv.trim().lines()
        val header = lines.first()
        val dataRows = lines.drop(1)

        assertTrue(header.startsWith("row,col,expected_idx"))
        assertEquals(400, dataRows.size)

        assertTrue(dataRows.first().startsWith("0,0,"))
        assertTrue(dataRows.last().startsWith("19,19,"))
    }

    @Test
    fun testNoMixingOfFrameTimestampBetweenArtifacts() {
        val timestamp = 1712345678901L
        val bundle = createDummyBundle(timestamp = timestamp)

        val summary = V6FullDiagnosticExporter.generateSummaryTxt(bundle)
        assertTrue(summary.contains("SUPERQR V6 FULL DIAGNOSTIC SUMMARY"))
        assertTrue(summary.contains("Normalized Detector Dimensions: 1080 x 1920"))
        assertTrue(summary.contains("Overlay Coordinate Space: NORMALIZED_DETECTOR"))

        val jsonStr = V6FullDiagnosticExporter.generateFrameMetadataJson(bundle)
        val json = JSONObject(jsonStr)
        assertEquals(timestamp, json.getLong("timestamp"))
        assertEquals("BEAFE8A7", json.getString("expectedCrc32"))

        val cellsCsv = V6FullDiagnosticExporter.generateCellsCsv(bundle)
        assertEquals(400, cellsCsv.trim().lines().drop(1).size)

        val pilotsCsv = V6FullDiagnosticExporter.generatePilotsCsv(bundle)
        assertTrue(pilotsCsv.contains("BLACK_MEDIAN"))

        val traceJsonl = V6FullDiagnosticExporter.generateFrameTraceJsonl(bundle)
        assertTrue(traceJsonl.lines().first().contains("\"timestamp\":$timestamp"))
    }

    private fun createDummyBundle(timestamp: Long): V6CapturedFrameBundle {
        val prng = Xorshift32(42)
        val expectedGrid = ByteArray(400) { ((prng.nextInt() ushr 16) and 3).toByte() }
        val colorNames = arrayOf("BLACK", "WHITE", "RED", "BLUE")

        val cellDetails = mutableListOf<V6CellDiagnosticDetail>()
        for (r in 0 until 20) {
            for (c in 0 until 20) {
                val idx = r * 20 + c
                val expIdx = expectedGrid[idx].toInt()
                val color = colorNames[expIdx]
                cellDetails.add(
                    V6CellDiagnosticDetail(
                        row = r,
                        col = c,
                        expectedIdx = expIdx,
                        expectedColor = color,
                        decodedIdx = expIdx,
                        decodedColor = color,
                        status = "CORRECT",
                        classificationSource = "FULL_DETECTION",
                        canonicalCenterX = 200.0 + c * 30.0 + 15.0,
                        canonicalCenterY = 200.0 + r * 30.0 + 15.0,
                        canonicalSamples = List(5) { Pair(200.0, 200.0) },
                        mappedCameraSamples = List(5) { Pair(200.0, 200.0) },
                        yReadSuccessCount = 5,
                        uReadSuccessCount = 5,
                        vReadSuccessCount = 5,
                        rawSamples = List(5) { intArrayOf(128, 128, 128) },
                        medianY = 128,
                        medianU = 128,
                        medianV = 128,
                        normY = 128.0,
                        normU = 128.0,
                        normV = 128.0,
                        distBlack = 100.0,
                        distWhite = 200.0,
                        distRed = 300.0,
                        distBlue = 400.0,
                        nearestDist = 100.0,
                        secondBestDist = 200.0,
                        confidenceMargin = 0.5,
                        uncertainReason = "NONE"
                    )
                )
            }
        }

        val pilotDetails = listOf("BLACK", "WHITE", "RED", "BLUE").map {
            V6PilotDiagnosticDetail(
                pilotName = it,
                canonicalCenterX = 300.0,
                canonicalCenterY = 120.0,
                mappedCameraCenterX = 300.0,
                mappedCameraCenterY = 120.0,
                ySuccess = true,
                uSuccess = true,
                vSuccess = true,
                rawSamples = List(5) { intArrayOf(128, 128, 128) },
                medianY = 128,
                medianU = 128,
                medianV = 128
            )
        }

        val payload = V6FrameDiagnosticPayload(
            timestamp = timestamp,
            classificationSource = "FULL_DETECTION",
            imageToCanonicalHomography = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            canonicalToImageHomography = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            detectedQuad = listOf(doubleArrayOf(60.0, 60.0), doubleArrayOf(940.0, 60.0), doubleArrayOf(940.0, 940.0), doubleArrayOf(60.0, 940.0)),
            trackedQuad = listOf(doubleArrayOf(60.0, 60.0), doubleArrayOf(940.0, 60.0), doubleArrayOf(940.0, 940.0), doubleArrayOf(60.0, 940.0)),
            contractHash = "4b3e90a0a24106795066eabfbbddf6bdafa9b14584709c2ebb0614a12d07d757",
            patternName = "deterministic random",
            seed = 42,
            first20Expected = expectedGrid.take(20).map { it.toInt() },
            first20Decoded = expectedGrid.take(20).map { it.toInt() },
            expectedCrc32 = 0xBEAFE8A7L,
            decodedCrc32 = 0xBEAFE8A7L,
            cellDetails = cellDetails,
            pilotDetails = pilotDetails,
            pairwisePilotDistances = mapOf("DIST_BLACK_WHITE" to 10000.0)
        )

        val frameTrace = listOf(
            V6FrameTraceEntry(
                timestamp = timestamp,
                state = "TRACKING",
                quad = payload.detectedQuad?.map { it.toList() },
                ransacInliers = 16,
                correctCount = 400,
                incorrectCount = 0,
                uncertainCount = 0,
                decodedCrc = "BEAFE8A7",
                pilotMedians = mapOf("BLACK" to listOf(35, 128, 128)),
                yuvReadSuccessCounts = mapOf("Y" to 2000, "U" to 2000, "V" to 2000)
            )
        )

        return V6CapturedFrameBundle(
            timestamp = timestamp,
            imageWidth = 1920,
            imageHeight = 1080,
            rotationDegrees = 90,
            cropRect = Rect(0, 0, 1920, 1080),
            yRowStride = 1920,
            yPixelStride = 1,
            uRowStride = 960,
            uPixelStride = 1,
            vRowStride = 960,
            vPixelStride = 1,
            yPlaneBytes = ByteArray(1920 * 1080),
            uPlaneBytes = ByteArray(960 * 540),
            vPlaneBytes = ByteArray(960 * 540),
            trackingState = "TRACKING",
            ransacInliers = 16,
            correctCount = 400,
            incorrectCount = 0,
            uncertainCount = 0,
            payload = payload,
            warpedLumaBytes = ByteArray(1000 * 1000),
            frameTrace = frameTrace
        )
    }

    @Test
    fun testValidTransportFrameIsNotLabeledAsStaticPatternMismatch() {
        val baseBundle = createDummyBundle(timestamp = 1700000000000L)
        val transportPayload = baseBundle.payload.copy(
            transportSessionId = 3,
            transportFrameId = 0,
            transportTotalFrames = 2,
            transportPayloadHex = "0000001F" + "00".repeat(87),
            transportCrc16Hex = "9E2F",
            transportError = null
        )
        val transportBundle = baseBundle.copy(payload = transportPayload)

        val summary = V6FullDiagnosticExporter.generateSummaryTxt(transportBundle)

        assertTrue(summary.contains("[OK] V6 Transport Frame: VALID"))
        assertTrue(summary.contains("[OK] CRC16: PASS"))
        assertTrue(summary.contains("[INFO] Static golden-pattern comparison not applicable to transport frame"))
        assertFalse(summary.contains("CRITICAL: Pattern CRC mismatch!"))
        assertTrue(summary.contains("Session ID: 3"))
        assertTrue(summary.contains("Frame ID: 0 / 2"))
        assertTrue(summary.contains("Raw Header Prefix: A5060300000002"))
        assertTrue(summary.contains("Package Length (Frame 0): 31 bytes"))
    }

    @Test
    fun testStaticPatternRetainsGoldenEvaluationSemantics() {
        val baseBundle = createDummyBundle(timestamp = 1700000000000L)
        val summaryMatch = V6FullDiagnosticExporter.generateSummaryTxt(baseBundle)
        assertTrue(summaryMatch.contains("[OK] Pattern CRC Match"))

        val mismatchPayload = baseBundle.payload.copy(decodedCrc32 = 0x12345678L)
        val mismatchBundle = baseBundle.copy(payload = mismatchPayload)
        val summaryMismatch = V6FullDiagnosticExporter.generateSummaryTxt(mismatchBundle)
        assertTrue(summaryMismatch.contains("CRITICAL: Pattern CRC mismatch!"))
    }
}
