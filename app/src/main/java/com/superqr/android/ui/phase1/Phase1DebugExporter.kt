package com.superqr.android.ui.phase1

import android.content.Context
import android.graphics.Bitmap
import com.superqr.android.ui.scanner.V7DebugExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles a single physical-lab moment for later reproduction.
 *
 * Includes the exact decoder-input frame (when available from the decoder
 * input debug view), plus enough geometry/timing/identity metadata to explain
 * why acquisition succeeded or failed at that instant, without depending on
 * the production V7 scanner's report shape.
 */
object Phase1DebugExporter {
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    fun exportBundle(
        context: Context,
        decoderInputFrame: Bitmap?,
        diagnostics: Phase1LiveDiagnostics,
        observationLines: List<String>,
    ): File {
        val zipFile = File(context.cacheDir, "superqr-phase1-debug-${stamp()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            if (decoderInputFrame != null) {
                zip.putNextEntry(ZipEntry("decoder-input.png"))
                decoderInputFrame.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
            zip.putText("diagnostics.json", buildDiagnosticsJson(diagnostics, decoderInputFrame != null))
            if (observationLines.isNotEmpty()) zip.putText("observations.jsonl", observationLines.joinToString("\n"))
        }
        return zipFile
    }

    fun shareBundle(context: Context, file: File) = V7DebugExporter.shareFile(context, file, "application/zip")

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildDiagnosticsJson(d: Phase1LiveDiagnostics, hasFrame: Boolean): String {
        val obj = JSONObject()
        obj.put("role", "android_phase1_receiver")
        obj.put("exported_at_ms", System.currentTimeMillis())
        obj.put("has_decoder_input_frame", hasFrame)
        obj.put("frame_width", d.frameWidth)
        obj.put("frame_height", d.frameHeight)
        obj.put("crop_left", d.cropLeft)
        obj.put("crop_top", d.cropTop)
        obj.put("crop_right", d.cropRight)
        obj.put("crop_bottom", d.cropBottom)
        obj.put("rotation_degrees", d.rotationDegrees)
        obj.put("sensor_timestamp_ns", d.sensorTimestampNs)
        obj.put("arrival_ns", d.arrivalNs)
        obj.put("mode", d.mode.name)
        obj.put("active_profile", d.activeProfileName)
        obj.put("acquisition_source", d.acquisitionSource)
        obj.put("tracking_state", d.trackingState.name)
        obj.put("run_token", d.runToken ?: JSONObject.NULL)
        obj.put("frame_index", d.frameIndex ?: JSONObject.NULL)
        obj.put("acquisition_ms", d.acquisitionMs ?: JSONObject.NULL)
        obj.put("sync_ms", d.syncMs ?: JSONObject.NULL)
        obj.put("payload_ms", d.payloadMs ?: JSONObject.NULL)
        obj.put("pipeline_ms", d.pipelineMs)
        obj.put("homography", d.homography?.let { h -> JSONArray(h.toList()) } ?: JSONObject.NULL)
        obj.put(
            "detected_quad",
            d.detectedQuad?.let { quad ->
                JSONArray().apply { quad.forEach { point -> put(JSONArray(point.toList())) } }
            } ?: JSONObject.NULL,
        )
        return obj.toString(2)
    }
}
