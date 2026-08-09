package com.superqr.android.ui.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.core.content.FileProvider
import com.superqr.android.vision.v7.transport.V7DebugSnapshot
import com.superqr.android.vision.v7.transport.V7OpticalProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Capture/share/export helpers for optical debugging and V7.0 measurement. */
object V7DebugExporter {
    data class Report(
        val measurementSchemaVersion: Int,
        val runId: String,
        val profile: V7OpticalProfile,
        val cameraState: String,
        val trackingState: String,
        val classificationSource: String,
        val borderFound: Boolean,
        val orientationResolved: Boolean,
        val detectorMs: Number,
        val calibrated: Int,
        val validSamples: Int,
        val confidentCells: Int,
        val rawErasures: Int,
        val analyzedFrames: Int,
        val skippedErasures: Int,
        val headerValid: Int,
        val headerInvalid: Int,
        val packAttempts: Int,
        val crcAttempts: Int,
        val crcCandidateAttempts: Int,
        val crcPass: Int,
        val crcFail: Int,
        val parserRejects: Int,
        val temporalRecoveredFrames: Int,
        val uniqueFrames: Int,
        val duplicates: Int,
        val conflicts: Int,
        val cameraFps: Double,
        val analysisFps: Double,
        val pipelineMs: Double,
        val analysisMs: Double,
        val profileMs: Double,
        val samplingMs: Double,
        val classificationMs: Double,
        val transportMs: Double,
        val usefulUniqueFps: Double,
        val acceptedPayloadBytes: Long,
        val decodedPayloadKiBs: Double,
        val measurementElapsedMs: Double,
        val analysisExceptionCount: Int,
        val lastAnalysisException: String?,
        val focusState: String,
        val lastError: String?,
    )

    private fun debugDir(context: Context): File = File(context.cacheDir, "superqr_debug").apply { mkdirs() }
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    fun captureWindow(activity: Activity, onComplete: (Result<File>) -> Unit) {
        val view = activity.window.decorView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            onComplete(Result.failure(IllegalStateException("window has no drawable size")))
            return
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    try {
                        val file = File(debugDir(activity), "capture-${stamp()}.png")
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        onComplete(Result.success(file))
                    } catch (t: Throwable) {
                        onComplete(Result.failure(t))
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    bitmap.recycle()
                    onComplete(Result.failure(IllegalStateException("PixelCopy failed: $result")))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            bitmap.recycle()
            onComplete(Result.failure(t))
        }
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share SuperQR debug"))
    }

    fun exportBundle(
        context: Context,
        screenCapture: File?,
        previewBitmap: Bitmap?,
        report: Report,
        snapshot: V7DebugSnapshot?,
        events: List<V7DebugEvent>,
        frameSummaries: List<V7DebugFrameSummary>,
    ): File {
        val dir = debugDir(context)
        val zipFile = File(dir, "superqr-debug-${stamp()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            if (screenCapture != null && screenCapture.exists()) {
                zip.putNextEntry(ZipEntry("screen.png"))
                screenCapture.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (previewBitmap != null) {
                zip.putNextEntry(ZipEntry("camera-preview.png"))
                previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }

            zip.putText("diagnostics.json", buildDiagnosticsJson(report, snapshot, events.size, frameSummaries))
            zip.putText("events.csv", buildEventsCsv(events))
            zip.putText("frame-summary.csv", buildFrameSummaryCsv(frameSummaries))

            if (snapshot != null) {
                zip.putText("cells.csv", buildCellsCsv(report.profile, snapshot))
                zip.putText("calibration.csv", buildCalibrationCsv(snapshot))
            }
        }
        return zipFile
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildDiagnosticsJson(
        report: Report,
        snapshot: V7DebugSnapshot?,
        eventCount: Int,
        frameSummaries: List<V7DebugFrameSummary>,
    ): String {
        val obj = JSONObject()
        obj.put("measurement_schema_version", report.measurementSchemaVersion)
        obj.put("run_id", report.runId)
        obj.put("timestamp_ms", System.currentTimeMillis())
        obj.put("role", "android_receiver")
        obj.put("profile_key", report.profile.key)
        obj.put("profile_id", report.profile.id)
        obj.put("grid", report.profile.grid)
        obj.put("colors", report.profile.colorCount)
        obj.put("frame_bytes", report.profile.frameSize)
        obj.put("payload_bytes", report.profile.payloadSize)
        obj.put("camera_state", report.cameraState)
        obj.put("tracking_state", report.trackingState)
        obj.put("classification_source", report.classificationSource)
        obj.put("border_found", report.borderFound)
        obj.put("orientation_resolved", report.orientationResolved)
        obj.put("detector_ms", report.detectorMs)
        obj.put("calibrated", report.calibrated)
        obj.put("valid_samples", report.validSamples)
        obj.put("confident_cells", report.confidentCells)
        obj.put("raw_erasures", report.rawErasures)
        obj.put("frames_analyzed", report.analyzedFrames)
        obj.put("skipped_erasures", report.skippedErasures)
        obj.put("header_valid", report.headerValid)
        obj.put("header_invalid", report.headerInvalid)
        obj.put("pack_attempts", report.packAttempts)
        obj.put("crc_frames_attempted", report.crcAttempts)
        obj.put("crc_candidate_attempts", report.crcCandidateAttempts)
        obj.put("crc_pass", report.crcPass)
        obj.put("crc_fail", report.crcFail)
        obj.put("parser_rejects", report.parserRejects)
        obj.put("temporal_recovered_frames", report.temporalRecoveredFrames)
        obj.put("unique_frames", report.uniqueFrames)
        obj.put("duplicates", report.duplicates)
        obj.put("conflicts", report.conflicts)
        obj.put("camera_delivered_fps", report.cameraFps)
        obj.put("analysis_fps", report.analysisFps)
        obj.put("pipeline_ms", report.pipelineMs)
        obj.put("v7_total_ms", report.analysisMs)
        obj.put("v7_profile_ms", report.profileMs)
        obj.put("v7_sampling_ms", report.samplingMs)
        obj.put("v7_classification_ms", report.classificationMs)
        obj.put("v7_transport_ms", report.transportMs)
        obj.put("useful_unique_fps", report.usefulUniqueFps)
        obj.put("accepted_payload_bytes", report.acceptedPayloadBytes)
        obj.put("decoded_payload_kib_s", report.decodedPayloadKiBs)
        obj.put("session_elapsed_ms", report.measurementElapsedMs)
        obj.put("analysis_exception_count", report.analysisExceptionCount)
        obj.put("last_analysis_exception", report.lastAnalysisException ?: JSONObject.NULL)
        obj.put("focus_state", report.focusState)
        obj.put("last_error", report.lastError ?: JSONObject.NULL)
        obj.put("history_event_count", eventCount)

        val summaryJson = JSONArray()
        frameSummaries.forEach { s ->
            summaryJson.put(JSONObject().apply {
                put("profile_id", s.profileId)
                put("session_id", s.sessionId)
                put("frame_id", s.frameId)
                put("total_frames", s.totalFrames)
                put("observations", s.observations)
                put("crc_pass", s.crcPass)
                put("crc_fail", s.crcFail)
                put("header_only", s.headerOnly)
                put("min_raw_erasures", s.minRawErasures)
                put("max_raw_erasures", s.maxRawErasures)
                put("last_candidate_passed", s.lastCandidatePassed ?: JSONObject.NULL)
            })
        }
        obj.put("frame_summaries", summaryJson)

        snapshot?.let { snap ->
            obj.put("classifier_max_distance", snap.maxDistanceThreshold)
            obj.put("classifier_margin_threshold", snap.marginThreshold)
            obj.put("inverse_homography", JSONArray(snap.finalInvHomography.toList()))

            val centers = JSONArray()
            snap.calibrationCenters.forEachIndexed { index, c ->
                centers.put(JSONObject().apply {
                    put("symbol", index)
                    put("y", c.getOrElse(0) { 128 })
                    put("u", c.getOrElse(1) { 128 })
                    put("v", c.getOrElse(2) { 128 })
                })
            }
            obj.put("calibration_centers", centers)

            val t = snap.transport
            val transport = JSONObject()
            transport.put("header_valid", t.headerValid)
            transport.put("header_error", t.headerError ?: JSONObject.NULL)
            transport.put("raw_erasures", t.rawErasures)
            transport.put("temporal_observations", t.temporalObservations)
            transport.put("temporal_filled_cells", t.temporalFilledCells)
            transport.put("temporal_overridden_cells", t.temporalOverriddenCells)
            transport.put("temporal_consensus_cells", t.temporalConsensusCells)
            transport.put("remaining_erasures", t.remainingErasures)
            transport.put("pack_attempted", t.packAttempted)
            transport.put("crc_attempted", t.crcAttempted)
            transport.put("crc_candidate_attempts", t.crcCandidateAttempts)
            transport.put("crc_passed", t.crcPassed)
            transport.put("candidate_passed", t.candidatePassed ?: JSONObject.NULL)
            transport.put("received_crc32", t.receivedCrc32?.let { "0x%08X".format(it) } ?: JSONObject.NULL)
            transport.put("computed_crc32", t.computedCrc32?.let { "0x%08X".format(it) } ?: JSONObject.NULL)
            transport.put("rejection_reason", t.rejectionReason ?: JSONObject.NULL)
            t.header?.let { h ->
                transport.put("session_id", h.sessionId)
                transport.put("frame_id", h.frameId)
                transport.put("total_frames", h.totalFrames)
                transport.put("payload_len", h.payloadLen)
            }
            obj.put("last_transport", transport)
        }
        return obj.toString(2)
    }

    private fun buildCellsCsv(profile: V7OpticalProfile, snapshot: V7DebugSnapshot): String = buildString {
        appendLine("index,row,col,valid,probe_mask,raw_symbol,fill_symbol,stable_symbol,second_symbol,best_distance,second_distance,y,u,v")
        for (i in snapshot.symbols.indices) {
            val row = i / profile.grid
            val col = i % profile.grid
            val fill = snapshot.fillOnlySymbols?.getOrNull(i)?.toInt() ?: -1
            val stable = snapshot.stabilizedSymbols?.getOrNull(i)?.toInt() ?: -1
            val probeMask = snapshot.probeValidityMask.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
            append(i).append(',')
                .append(row).append(',')
                .append(col).append(',')
                .append(snapshot.validMask.getOrNull(i)?.toInt() ?: 0).append(',')
                .append(probeMask).append(',')
                .append(snapshot.symbols[i].toInt()).append(',')
                .append(fill).append(',')
                .append(stable).append(',')
                .append(snapshot.secondBestSymbols.getOrNull(i)?.toInt() ?: -1).append(',')
                .append(snapshot.bestDistances.getOrNull(i) ?: -1).append(',')
                .append(snapshot.secondBestDistances.getOrNull(i) ?: -1).append(',')
                .append(snapshot.sampleY.getOrNull(i) ?: -1).append(',')
                .append(snapshot.sampleU.getOrNull(i) ?: -1).append(',')
                .append(snapshot.sampleV.getOrNull(i) ?: -1)
                .appendLine()
        }
    }

    private fun buildCalibrationCsv(snapshot: V7DebugSnapshot): String = buildString {
        appendLine("symbol,y,u,v")
        snapshot.calibrationCenters.forEachIndexed { index, c ->
            append(index).append(',')
                .append(c.getOrElse(0) { 128 }).append(',')
                .append(c.getOrElse(1) { 128 }).append(',')
                .append(c.getOrElse(2) { 128 })
                .appendLine()
        }
    }

    private fun buildEventsCsv(events: List<V7DebugEvent>): String = buildString {
        appendLine("analysis,profile_id,profile_key,session_id,frame_id,total_frames,payload_len,camera_delivered_fps,analysis_fps,pipeline_ms,detector_ms,v7_total_ms,v7_profile_ms,v7_sampling_ms,v7_classification_ms,v7_transport_ms,useful_unique_fps,decoded_payload_kib_s,raw_erasures,remaining_erasures,temporal_obs,temporal_filled,temporal_overridden,header_valid,header_error,crc_candidate_attempts,crc_passed,candidate_passed,received_crc32,computed_crc32,accepted_frame,rejection")
        for (e in events) {
            append(e.analysisIndex).append(',')
                .append(e.profileId).append(',')
                .append(csv(e.profileKey)).append(',')
                .append(e.sessionId ?: "").append(',')
                .append(e.frameId ?: "").append(',')
                .append(e.totalFrames ?: "").append(',')
                .append(e.payloadLen ?: "").append(',')
                .append(e.cameraDeliveredFps).append(',')
                .append(e.analysisFps).append(',')
                .append(e.pipelineMs).append(',')
                .append(e.detectorMs).append(',')
                .append(e.v7TotalMs).append(',')
                .append(e.v7ProfileMs).append(',')
                .append(e.v7SamplingMs).append(',')
                .append(e.v7ClassificationMs).append(',')
                .append(e.v7TransportMs).append(',')
                .append(e.usefulUniqueFps).append(',')
                .append(e.decodedPayloadKiBs).append(',')
                .append(e.rawErasures).append(',')
                .append(e.remainingErasures).append(',')
                .append(e.temporalObservations).append(',')
                .append(e.temporalFilled).append(',')
                .append(e.temporalOverridden).append(',')
                .append(e.headerValid).append(',')
                .append(csv(e.headerError)).append(',')
                .append(e.crcCandidateAttempts).append(',')
                .append(e.crcPassed).append(',')
                .append(csv(e.candidatePassed)).append(',')
                .append(e.receivedCrc32?.let { "0x%08X".format(it) } ?: "").append(',')
                .append(e.computedCrc32?.let { "0x%08X".format(it) } ?: "").append(',')
                .append(e.acceptedFrameId ?: "").append(',')
                .append(csv(e.rejectionReason))
                .appendLine()
        }
    }

    private fun buildFrameSummaryCsv(summaries: List<V7DebugFrameSummary>): String = buildString {
        appendLine("profile_id,session_id,frame_id,total_frames,observations,crc_pass,crc_fail,header_only,min_raw_erasures,max_raw_erasures,last_candidate_passed")
        for (s in summaries) {
            append(s.profileId).append(',')
                .append(s.sessionId).append(',')
                .append(s.frameId).append(',')
                .append(s.totalFrames).append(',')
                .append(s.observations).append(',')
                .append(s.crcPass).append(',')
                .append(s.crcFail).append(',')
                .append(s.headerOnly).append(',')
                .append(s.minRawErasures).append(',')
                .append(s.maxRawErasures).append(',')
                .append(csv(s.lastCandidatePassed))
                .appendLine()
        }
    }

    private fun csv(value: String?): String {
        if (value == null) return ""
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
