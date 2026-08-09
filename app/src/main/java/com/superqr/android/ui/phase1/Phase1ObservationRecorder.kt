package com.superqr.android.ui.phase1

import android.content.Context
import com.superqr.android.ui.scanner.V7DebugExporter
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class Phase1RunSnapshot(
    val runId: String,
    val observations: Int,
    val uniqueFrames: Int,
    val validFrames: Int,
    val postFecFrames: Int,
    val innovativeBytes: Long,
    val elapsedSeconds: Double,
    val meanPipelineMs: Double,
) {
    val goodputKibS: Double get() = if (elapsedSeconds > 0.0) innovativeBytes / elapsedSeconds / 1024.0 else 0.0
}

class Phase1ObservationRecorder {
    private val lines = ArrayList<String>(4096)
    private val seenFrames = BooleanArray(256)
    private var startedNs = System.nanoTime()
    private var validFrames = 0
    private var postFecFrames = 0
    private var uniqueFrames = 0
    private var innovativeBytes = 0L
    private var pipelineMsTotal = 0.0
    var runId: String = UUID.randomUUID().toString()
        private set

    @Synchronized
    fun reset() {
        lines.clear(); seenFrames.fill(false)
        startedNs = System.nanoTime(); validFrames = 0; postFecFrames = 0
        uniqueFrames = 0; innovativeBytes = 0; pipelineMsTotal = 0.0
        runId = UUID.randomUUID().toString()
    }

    @Synchronized
    fun record(
        profile: Phase1Profile,
        dwellEpochs: Int,
        completedNs: Long,
        frameIndex: Long?,
        observedBits: Int,
        bitErrors: Int,
        erasedBits: Int,
        frameValid: Boolean,
        postFecValid: Boolean,
        pipelineMs: Double,
        allocationBytes: Long,
        gcEvents: Long,
        errorCellIndexes: IntArray? = null,
        errorCellCount: Int = 0,
        erasureCellIndexes: IntArray? = null,
        erasureCellCount: Int = 0,
        extra: Map<String, Any?> = emptyMap(),
    ): Phase1RunSnapshot {
        val normalizedIndex = frameIndex?.takeIf { it in 0..255 }?.toInt()
        val novel = postFecValid && normalizedIndex != null && !seenFrames[normalizedIndex]
        val useful = if (novel) profile.usefulBytes else 0
        if (novel) { seenFrames[normalizedIndex] = true; uniqueFrames++; innovativeBytes += useful }
        if (frameValid) validFrames++
        if (postFecValid) postFecFrames++
        pipelineMsTotal += pipelineMs

        val json = JSONObject()
            .put("profile", profile.name)
            .put("run_id", runId)
            .put("dwell_epochs", dwellEpochs)
            .put("completed_ns", completedNs)
            .put("observed_bits", observedBits)
            .put("bit_errors", bitErrors)
            .put("erased_bits", erasedBits)
            .put("frame_valid", frameValid)
            .put("post_fec_valid", postFecValid)
            .put("innovative_bytes", useful)
            .put("pipeline_ms", pipelineMs)
            .put("allocation_bytes", allocationBytes.coerceAtLeast(0))
            .put("gc_events", gcEvents.coerceAtLeast(0))
        if (frameIndex != null) json.put("frame_index", frameIndex)
        if (errorCellIndexes != null && errorCellCount > 0) {
            val values = JSONArray()
            for (index in 0 until errorCellCount) values.put(errorCellIndexes[index])
            json.put("error_cells", values)
        }
        if (erasureCellIndexes != null && erasureCellCount > 0) {
            val values = JSONArray()
            for (index in 0 until erasureCellCount) values.put(erasureCellIndexes[index])
            json.put("erasure_cells", values)
        }
        for ((key, value) in extra) json.put(key, value)
        lines += json.toString()
        return snapshot(completedNs)
    }

    @Synchronized
    fun snapshot(nowNs: Long = System.nanoTime()): Phase1RunSnapshot = Phase1RunSnapshot(
        runId = runId,
        observations = lines.size,
        uniqueFrames = uniqueFrames,
        validFrames = validFrames,
        postFecFrames = postFecFrames,
        innovativeBytes = innovativeBytes,
        elapsedSeconds = ((nowNs - startedNs).coerceAtLeast(0L)) / 1_000_000_000.0,
        meanPipelineMs = if (lines.isEmpty()) 0.0 else pipelineMsTotal / lines.size,
    )

    internal fun jsonLinesForTest(): List<String> = synchronized(this) { lines.toList() }

    @Synchronized
    fun exportAndShare(context: Context, profileName: String): File {
        val safeProfile = profileName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(context.cacheDir, "v7-phase1-$safeProfile-$runId.jsonl")
        file.bufferedWriter().use { writer ->
            for (line in lines) { writer.write(line); writer.newLine() }
        }
        V7DebugExporter.shareFile(context, file, "application/x-ndjson")
        return file
    }
}
