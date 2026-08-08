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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Capture/share/export helpers for optical debugging. */
object V7DebugExporter {
    data class Report(
        val profile: V7OpticalProfile,
        val cameraState: String,
        val trackingState: String,
        val classificationSource: String,
        val borderFound: Boolean,
        val orientationResolved: Boolean,
        val detectorMs: Double,
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
        val crcPass: Int,
        val crcFail: Int,
        val parserRejects: Int,
        val temporalRecoveredFrames: Int,
        val uniqueFrames: Int,
        val duplicates: Int,
        val conflicts: Int,
        val cameraFps: Double,
        val analysisFps: Double,
        val analysisMs: Double,
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
            zip.putNextEntry(ZipEntry("diagnostics.json"))
            zip.write(buildDiagnosticsJson(report, snapshot).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (snapshot != null) {
                zip.putNextEntry(ZipEntry("cells.csv"))
                zip.write(buildCellsCsv(report.profile, snapshot).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun buildDiagnosticsJson(report: Report, snapshot: V7DebugSnapshot?): String {
        val obj = JSONObject()
        obj.put("timestamp_ms", System.currentTimeMillis())
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
        obj.put("crc_attempts", report.crcAttempts)
        obj.put("crc_pass", report.crcPass)
        obj.put("crc_fail", report.crcFail)
        obj.put("parser_rejects", report.parserRejects)
        obj.put("temporal_recovered_frames", report.temporalRecoveredFrames)
        obj.put("unique_frames", report.uniqueFrames)
        obj.put("duplicates", report.duplicates)
        obj.put("conflicts", report.conflicts)
        obj.put("camera_fps", report.cameraFps)
        obj.put("analysis_fps", report.analysisFps)
        obj.put("analysis_ms", report.analysisMs)
        obj.put("focus_state", report.focusState)
        obj.put("last_error", report.lastError ?: JSONObject.NULL)

        snapshot?.let { snap ->
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
            transport.put("crc_passed", t.crcPassed)
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
        appendLine("index,row,col,valid,raw_symbol,stable_symbol,second_symbol,best_distance,second_distance,y,u,v")
        for (i in snapshot.symbols.indices) {
            val row = i / profile.grid
            val col = i % profile.grid
            val stable = snapshot.stabilizedSymbols?.getOrNull(i)?.toInt() ?: -1
            append(i).append(',')
                .append(row).append(',')
                .append(col).append(',')
                .append(snapshot.validMask.getOrNull(i)?.toInt() ?: 0).append(',')
                .append(snapshot.symbols[i].toInt()).append(',')
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
}
