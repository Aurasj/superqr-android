package com.superqr.android.vision.v6.replay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V6ReplayMetricsTest {

    @Test
    fun testAggregationEmptyResults() {
        val summary = V6ReplayAggregator.aggregate(emptyList())
        assertEquals(0, summary.totalCases)
        assertEquals(0.0, summary.borderFoundRate, 0.0001)
        assertEquals(0.0, summary.orientationResolvedRate, 0.0001)
        assertEquals(0.0, summary.packetSuccessRate, 0.0001)
        assertNull(summary.averageSER)
        assertEquals(0.0, summary.meanProcessingMs, 0.0001)
        assertEquals(0.0, summary.medianProcessingMs, 0.0001)
        assertEquals(0.0, summary.p95ProcessingMs, 0.0001)
    }

    @Test
    fun testAggregationCalculation() {
        val r1 = V6ReplayResult(
            label = "frame1",
            zipFileName = "f1.zip",
            borderFound = true,
            orientationResolved = true,
            classificationSource = "FULL_DETECTION",
            correctSymbols = 400,
            symbolErrors = 0,
            symbolErrorRate = 0.0,
            uncertainCount = 0,
            uncertainRate = 0.0,
            parseAttempted = true,
            transportValid = true,
            sessionId = 1,
            frameId = 0,
            totalFrames = 2,
            expectedSessionId = 1,
            expectedFrameId = 0,
            expectedTotalFrames = 2,
            packetSuccess = true,
            processingTimeMs = 10L,
            failureReason = null
        )

        val r2 = V6ReplayResult(
            label = "frame2",
            zipFileName = "f2.zip",
            borderFound = true,
            orientationResolved = true,
            classificationSource = "FULL_DETECTION",
            correctSymbols = 380,
            symbolErrors = 20,
            symbolErrorRate = 0.05,
            uncertainCount = 0,
            uncertainRate = 0.0,
            parseAttempted = true,
            transportValid = false,
            sessionId = null,
            frameId = null,
            totalFrames = null,
            expectedSessionId = 1,
            expectedFrameId = 1,
            expectedTotalFrames = 2,
            packetSuccess = false,
            processingTimeMs = 20L,
            failureReason = "CRC Error"
        )

        val r3 = V6ReplayResult(
            label = "frame3",
            zipFileName = "f3.zip",
            borderFound = false,
            orientationResolved = false,
            classificationSource = "NONE",
            correctSymbols = null,
            symbolErrors = null,
            symbolErrorRate = null,
            uncertainCount = 0,
            uncertainRate = 0.0,
            parseAttempted = false,
            transportValid = false,
            sessionId = null,
            frameId = null,
            totalFrames = null,
            expectedSessionId = null,
            expectedFrameId = null,
            expectedTotalFrames = null,
            packetSuccess = false,
            processingTimeMs = 30L,
            failureReason = "No border"
        )

        val results = listOf(r1, r2, r3)
        val summary = V6ReplayAggregator.aggregate(results)

        assertEquals(3, summary.totalCases)
        assertEquals(2, summary.borderFoundCount)
        assertEquals(2.0 / 3.0, summary.borderFoundRate, 0.0001)
        assertEquals(2, summary.orientationResolvedCount)
        assertEquals(2.0 / 3.0, summary.orientationResolvedRate, 0.0001)
        assertEquals(1, summary.transportValidCount)
        assertEquals(1, summary.packetSuccessCount)
        assertEquals(1.0 / 3.0, summary.packetSuccessRate, 0.0001)

        // averageSER of non-null (0.0 and 0.05) -> 0.025
        assertNotNull(summary.averageSER)
        assertEquals(0.025, summary.averageSER!!, 0.0001)

        // processing times: 10, 20, 30
        assertEquals(20.0, summary.meanProcessingMs, 0.0001)
        assertEquals(20.0, summary.medianProcessingMs, 0.0001)
        assertEquals(30.0, summary.p95ProcessingMs, 0.0001)
    }

    @Test
    fun testExporterFormatting() {
        val r = V6ReplayResult(
            label = "test_case",
            zipFileName = "test.zip",
            borderFound = true,
            orientationResolved = true,
            classificationSource = "FULL_DETECTION",
            correctSymbols = 390,
            symbolErrors = 10,
            symbolErrorRate = 0.025,
            uncertainCount = 0,
            uncertainRate = 0.0,
            parseAttempted = true,
            transportValid = true,
            sessionId = 1,
            frameId = 0,
            totalFrames = 2,
            expectedSessionId = 1,
            expectedFrameId = 0,
            expectedTotalFrames = 2,
            packetSuccess = true,
            processingTimeMs = 15L,
            failureReason = null
        )
        val summary = V6ReplayAggregator.aggregate(listOf(r))

        val csv = V6ReplayReportExporter.generateResultsCsv(listOf(r))
        assertTrue(csv.contains("test_case,test.zip,true,true,FULL_DETECTION,390,10,0.0250,0,true,1,0,2,0,true,15,\"\""))

        val summaryTxt = V6ReplayReportExporter.generateSummaryTxt(summary)
        assertTrue(summaryTxt.contains("Total Cases:               1"))
        assertTrue(summaryTxt.contains("Border Found Rate:         100.00%"))

        val json = V6ReplayReportExporter.generateResultsJson(summary, listOf(r))
        assertTrue(json.contains("\"totalCases\": 1"))
        assertTrue(json.contains("\"label\": \"test_case\""))
    }
}
