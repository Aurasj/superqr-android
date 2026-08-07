package com.superqr.android.vision.v6.replay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object V6ReplayReportExporter {

    fun exportReports(
        results: List<V6ReplayResult>,
        summary: V6ReplaySummary,
        outputDir: File
    ) {
        outputDir.mkdirs()

        val summaryFile = File(outputDir, "summary.txt")
        val csvFile = File(outputDir, "results.csv")
        val jsonFile = File(outputDir, "results.json")

        summaryFile.writeText(generateSummaryTxt(summary), Charsets.UTF_8)
        csvFile.writeText(generateResultsCsv(results), Charsets.UTF_8)
        jsonFile.writeText(generateResultsJson(summary, results), Charsets.UTF_8)
    }

    fun generateSummaryTxt(summary: V6ReplaySummary): String {
        val total = summary.totalCases
        val borderRateStr = "%.2f%%".format(Locale.US, summary.borderFoundRate * 100)
        val orientRateStr = "%.2f%%".format(Locale.US, summary.orientationResolvedRate * 100)
        val psrStr = "%.2f%%".format(Locale.US, summary.packetSuccessRate * 100)
        val avgSerStr = summary.averageSER?.let { "%.4f".format(Locale.US, it) } ?: "N/A"
        val meanMsStr = "%.2f ms".format(Locale.US, summary.meanProcessingMs)
        val medianMsStr = "%.2f ms".format(Locale.US, summary.medianProcessingMs)
        val p95MsStr = "%.2f ms".format(Locale.US, summary.p95ProcessingMs)

        return """
==================================================
SuperQR V6 Offline Replay Benchmark Summary
==================================================
Total Cases:               $total
Border Found Rate:         $borderRateStr (${summary.borderFoundCount}/$total)
Orientation Resolved Rate: $orientRateStr (${summary.orientationResolvedCount}/$total)
Packet Success Rate (PSR): $psrStr (${summary.packetSuccessCount}/$total)
Average SER:               $avgSerStr
Mean Processing Time:      $meanMsStr
Median Processing Time:    $medianMsStr
P95 Processing Time:       $p95MsStr
CRC Valid Frames:          ${summary.totalCrcValidFrames}
CRC Invalid Candidates:    ${summary.totalCrcInvalidFrames}
==================================================
""".trimIndent() + "\n"
    }

    fun generateResultsCsv(results: List<V6ReplayResult>): String {
        val sb = StringBuilder()
        sb.append("label,zip,border_found,orientation_resolved,classification_source,correct_symbols,symbol_errors,ser,uncertain,transport_valid,session_id,frame_id,total_frames,expected_frame_id,packet_success,processing_ms,failure_reason\n")

        for (r in results) {
            val serStr = r.symbolErrorRate?.let { "%.4f".format(Locale.US, it) } ?: ""
            val reasonEscaped = (r.failureReason ?: "").replace("\"", "\"\"")
            sb.append("${r.label},")
                .append("${r.zipFileName},")
                .append("${r.borderFound},")
                .append("${r.orientationResolved},")
                .append("${r.classificationSource},")
                .append("${r.correctSymbols ?: ""},")
                .append("${r.symbolErrors ?: ""},")
                .append("$serStr,")
                .append("${r.uncertainCount},")
                .append("${r.transportValid},")
                .append("${r.sessionId ?: ""},")
                .append("${r.frameId ?: ""},")
                .append("${r.totalFrames ?: ""},")
                .append("${r.expectedFrameId ?: ""},")
                .append("${r.packetSuccess},")
                .append("${r.processingTimeMs},")
                .append("\"$reasonEscaped\"\n")
        }
        return sb.toString()
    }

    fun generateResultsJson(summary: V6ReplaySummary, results: List<V6ReplayResult>): String {
        val root = JSONObject()

        val summaryObj = JSONObject()
        summaryObj.put("totalCases", summary.totalCases)
        summaryObj.put("borderFoundCount", summary.borderFoundCount)
        summaryObj.put("borderFoundRate", summary.borderFoundRate)
        summaryObj.put("orientationResolvedCount", summary.orientationResolvedCount)
        summaryObj.put("orientationResolvedRate", summary.orientationResolvedRate)
        summaryObj.put("parseAttemptedCount", summary.parseAttemptedCount)
        summaryObj.put("transportValidCount", summary.transportValidCount)
        summaryObj.put("packetSuccessCount", summary.packetSuccessCount)
        summaryObj.put("packetSuccessRate", summary.packetSuccessRate)
        summaryObj.put("averageSER", summary.averageSER ?: JSONObject.NULL)
        summaryObj.put("meanProcessingMs", summary.meanProcessingMs)
        summaryObj.put("medianProcessingMs", summary.medianProcessingMs)
        summaryObj.put("p95ProcessingMs", summary.p95ProcessingMs)
        summaryObj.put("totalCrcValidFrames", summary.totalCrcValidFrames)
        summaryObj.put("totalCrcInvalidFrames", summary.totalCrcInvalidFrames)

        root.put("summary", summaryObj)

        val resultsArr = JSONArray()
        for (r in results) {
            val obj = JSONObject()
            obj.put("label", r.label)
            obj.put("zipFileName", r.zipFileName)
            obj.put("borderFound", r.borderFound)
            obj.put("orientationResolved", r.orientationResolved)
            obj.put("classificationSource", r.classificationSource)
            obj.put("correctSymbols", r.correctSymbols ?: JSONObject.NULL)
            obj.put("symbolErrors", r.symbolErrors ?: JSONObject.NULL)
            obj.put("symbolErrorRate", r.symbolErrorRate ?: JSONObject.NULL)
            obj.put("uncertainCount", r.uncertainCount)
            obj.put("uncertainRate", r.uncertainRate)
            obj.put("parseAttempted", r.parseAttempted)
            obj.put("transportValid", r.transportValid)
            obj.put("sessionId", r.sessionId ?: JSONObject.NULL)
            obj.put("frameId", r.frameId ?: JSONObject.NULL)
            obj.put("totalFrames", r.totalFrames ?: JSONObject.NULL)
            obj.put("expectedSessionId", r.expectedSessionId ?: JSONObject.NULL)
            obj.put("expectedFrameId", r.expectedFrameId ?: JSONObject.NULL)
            obj.put("expectedTotalFrames", r.expectedTotalFrames ?: JSONObject.NULL)
            obj.put("packetSuccess", r.packetSuccess)
            obj.put("processingTimeMs", r.processingTimeMs)
            obj.put("failureReason", r.failureReason ?: JSONObject.NULL)
            resultsArr.put(obj)
        }
        root.put("results", resultsArr)

        return root.toString(2)
    }
}
