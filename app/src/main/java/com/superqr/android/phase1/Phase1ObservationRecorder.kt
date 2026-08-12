package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Phase1RunSnapshot(
    val campaignId: String,
    val runId: String,
    val runToken: Int?,
    val senderState: String,
    val syncStatus: String,
    val geometryState: String,
    val profileName: String,
    val expectedFrames: Int,
    val analyzedFrames: Int,
    val observations: Int,
    val uniqueFrames: Int,
    val validFrames: Int,
    val innerFecFrames: Int,
    val innovativeBytes: Long,
    val elapsedSeconds: Double,
    val meanPipelineMs: Double,
    val p95PipelineMs: Double,
    val cameraFps: Double,
    val captureWidth: Int,
    val captureHeight: Int,
    val bitErrorRate: Double,
    val erasureRate: Double,
    val lastFailure: String?,
    val failureSummary: String,
    val lastAcquisitionMs: Double? = null,
    val lastSyncMs: Double? = null,
    val lastPayloadMs: Double? = null,
) {
    val goodputKibS: Double get() = if (elapsedSeconds > 0.0) innovativeBytes / elapsedSeconds / 1024.0 else 0.0
    val rawValidYield: Double get() = if (observations > 0) validFrames.toDouble() / observations else 0.0
    val innerFecYield: Double get() = if (observations > 0) innerFecFrames.toDouble() / observations else 0.0
    val progress: Double get() = if (expectedFrames > 0) uniqueFrames.toDouble() / expectedFrames else 0.0
    val trackingState: Phase1TrackingState get() = Phase1TrackingState.fromSource(geometryState)
}

/** Phase 1 measurement recorder. It owns data, not Android sharing/UI. */
class Phase1ObservationRecorder {
    private val lines = ArrayList<String>(8192)
    private val seenFrames = BooleanArray(256)
    private val failureCounts = LinkedHashMap<String, Int>()
    private val pipelineWindow = DoubleArray(256)
    private var pipelineWindowCount = 0
    private var pipelineWindowCursor = 0
    private var firstAnalysisNs = 0L
    private var lastAnalysisNs = 0L
    private var runStartedNs = 0L
    private var runEndedNs = 0L
    private var validFrames = 0
    private var innerFecFrames = 0
    val currentUniqueFrames: Int get() = uniqueFrames
    val currentExpectedFrames: Int get() = expectedFrames
    private var uniqueFrames = 0
    private var innovativeBytes = 0L
    private var pipelineMsTotal = 0.0
    private var bitErrorsTotal = 0L
    private var erasedBitsTotal = 0L
    private var observedBitsTotal = 0L
    private var currentProfile: Phase1Profile? = null
    private var expectedFrames = 256
    private var dwellEpochs = 3
    private var runToken: Int? = null
    private var senderState = "UNKNOWN"
    private var syncStatus = "SEARCHING"
    private var geometryState = "SEARCHING"
    private var lastFailure: String? = null
    private var analyzedFrames = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var observations = 0
    private var lastAcquisitionMs: Double? = null
    private var lastSyncMs: Double? = null
    private var lastPayloadMs: Double? = null

    var campaignId: String = UUID.randomUUID().toString()
        private set
    var runId: String = "UNSYNCED"
        private set

    val hasLines: Boolean
        @Synchronized get() = lines.isNotEmpty()

    val lineCount: Int
        @Synchronized get() = lines.size

    @Synchronized
    fun reset() {
        lines.clear()
        campaignId = UUID.randomUUID().toString()
        currentProfile = null
        runToken = null
        runId = "UNSYNCED"
        senderState = "UNKNOWN"
        syncStatus = "SEARCHING"
        geometryState = "SEARCHING"
        resetRunCounters()
        failureCounts.clear()
        lastFailure = null
        analyzedFrames = 0
        firstAnalysisNs = 0L
        lastAnalysisNs = 0L
    }

    @Synchronized
    fun observeSender(
        profile: Phase1Profile,
        envelope: V7LabRunEnvelope,
        sync: String,
        geometry: String,
        observedNs: Long = System.nanoTime(),
    ) {
        if (runToken != envelope.runToken || currentProfile?.id != profile.id) {
            currentProfile = profile
            runToken = envelope.runToken
            runId = "%04X".format(envelope.runToken)
            expectedFrames = envelope.frameCount
            dwellEpochs = envelope.dwellEpochs
            resetRunCounters()
            lastFailure = null
        }
        senderState = envelope.state.name
        syncStatus = sync
        geometryState = geometry
        if (envelope.state == V7LabRunState.DONE && runStartedNs > 0L && runEndedNs == 0L) {
            runEndedNs = observedNs.coerceAtLeast(runStartedNs)
        }
    }

    @Synchronized
    fun recordFailure(
        reason: String,
        completedNs: Long,
        pipelineMs: Double,
        geometry: String,
        sync: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Phase1RunSnapshot {
        if (senderState == "DONE") {
            if (runEndedNs > 0L && completedNs >= runEndedNs &&
                completedNs - runEndedNs <= POST_DONE_SEARCH_WINDOW_NS
            ) {
                val json = JSONObject()
                    .put("profile", "AUTO_SEARCH_NEXT")
                    .put("campaign_id", campaignId)
                    .put("run_id", "SEARCH_NEXT")
                    .put("run_token", JSONObject.NULL)
                    .put("previous_run_id", runId)
                    .put("previous_run_token", runToken ?: JSONObject.NULL)
                    .put("sender_state", "SEARCHING_NEXT")
                    .put("sync_status", sync)
                    .put("geometry_source", geometry)
                    .put("dwell_epochs", currentDwell())
                    .put("completed_ns", completedNs)
                    .put("pipeline_ms", pipelineMs)
                    .put("allocation_bytes", 0)
                    .put("gc_events", 0)
                    .put("scored", false)
                    .put("observed_bits", 0)
                    .put("bit_errors", 0)
                    .put("erased_bits", 0)
                    .put("frame_valid", false)
                    .put("post_fec_valid", false)
                    .put("raw_valid", false)
                    .put("inner_fec_valid", false)
                    .put("innovative_bytes", 0)
                    .put("failure_reason", reason)
                    .put("transition_diagnostic", true)
                putExtras(json, extra)
                lines += json.toString()
            }
            return snapshot(completedNs)
        }

        noteAnalysis(completedNs, pipelineMs)
        geometryState = geometry
        syncStatus = sync
        lastFailure = reason
        failureCounts[reason] = (failureCounts[reason] ?: 0) + 1
        updateCapture(extra)
        val profile = currentProfile
        val json = baseJson(profile?.name ?: "AUTO_UNSYNCED", completedNs, pipelineMs, scored = false)
            .put("observed_bits", 0)
            .put("bit_errors", 0)
            .put("erased_bits", 0)
            .put("frame_valid", false)
            .put("post_fec_valid", false)
            .put("raw_valid", false)
            .put("inner_fec_valid", false)
            .put("innovative_bytes", 0)
            .put("failure_reason", reason)
        putExtras(json, extra)
        lines += json.toString()
        return snapshot(completedNs)
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
        envelope: V7LabRunEnvelope? = null,
        sync: String = "LEGACY",
        geometry: String = "UNKNOWN",
        failureReason: String? = null,
        errorCellIndexes: IntArray? = null,
        errorCellCount: Int = 0,
        erasureCellIndexes: IntArray? = null,
        erasureCellCount: Int = 0,
        extra: Map<String, Any?> = emptyMap(),
    ): Phase1RunSnapshot {
        if (envelope != null) {
            observeSender(profile, envelope, sync, geometry, completedNs)
        } else if (currentProfile == null) {
            currentProfile = profile
            expectedFrames = 256
            this.dwellEpochs = dwellEpochs
        }
        noteAnalysis(completedNs, pipelineMs)
        updateCapture(extra)
        observations++
        if (runStartedNs == 0L) runStartedNs = completedNs
        val normalizedIndex = frameIndex?.takeIf { it in 0..255 }?.toInt()
        val novel = postFecValid && normalizedIndex != null && !seenFrames[normalizedIndex]
        val useful = if (novel) profile.usefulBytes else 0
        if (novel) {
            seenFrames[normalizedIndex!!] = true
            uniqueFrames++
            innovativeBytes += useful
        }
        if (frameValid) validFrames++
        if (postFecValid) innerFecFrames++
        observedBitsTotal += observedBits
        bitErrorsTotal += bitErrors
        erasedBitsTotal += erasedBits
        lastFailure = failureReason
        if (failureReason != null) failureCounts[failureReason] = (failureCounts[failureReason] ?: 0) + 1

        val json = baseJson(profile.name, completedNs, pipelineMs, scored = true)
            .put("dwell_epochs", dwellEpochs)
            .put("observed_bits", observedBits)
            .put("bit_errors", bitErrors)
            .put("erased_bits", erasedBits)
            .put("frame_valid", frameValid)
            .put("post_fec_valid", postFecValid)
            .put("raw_valid", frameValid)
            .put("inner_fec_valid", postFecValid)
            .put("innovative_bytes", useful)
            .put("allocation_bytes", allocationBytes.coerceAtLeast(0))
            .put("gc_events", gcEvents.coerceAtLeast(0))
        if (frameIndex != null) json.put("frame_index", frameIndex)
        if (failureReason != null) json.put("failure_reason", failureReason)
        addIndexes(json, "error_cells", errorCellIndexes, errorCellCount)
        addIndexes(json, "erasure_cells", erasureCellIndexes, erasureCellCount)
        putExtras(json, extra)
        lines += json.toString()
        return snapshot(completedNs)
    }

    private fun baseJson(profile: String, completedNs: Long, pipelineMs: Double, scored: Boolean): JSONObject =
        JSONObject()
            .put("profile", profile)
            .put("campaign_id", campaignId)
            .put("run_id", runId)
            .put("run_token", runToken ?: JSONObject.NULL)
            .put("sender_state", senderState)
            .put("sync_status", syncStatus)
            .put("geometry_source", geometryState)
            .put("dwell_epochs", currentDwell())
            .put("completed_ns", completedNs)
            .put("pipeline_ms", pipelineMs)
            .put("allocation_bytes", 0)
            .put("gc_events", 0)
            .put("scored", scored)

    private fun currentDwell(): Int = dwellEpochs

    private fun noteAnalysis(completedNs: Long, pipelineMs: Double) {
        analyzedFrames++
        if (firstAnalysisNs == 0L) firstAnalysisNs = completedNs
        lastAnalysisNs = completedNs
        pipelineMsTotal += pipelineMs
        pipelineWindow[pipelineWindowCursor] = pipelineMs
        pipelineWindowCursor = (pipelineWindowCursor + 1) % pipelineWindow.size
        pipelineWindowCount = minOf(pipelineWindowCount + 1, pipelineWindow.size)
    }

    @Synchronized
    fun missedGaps(): String {
        val maxInclusive = expectedFrames - 1
        val ranges = mutableListOf<String>()
        var start = -1
        for (i in 0..maxInclusive) {
            if (!seenFrames[i]) {
                if (start < 0) start = i
            } else {
                if (start >= 0) {
                    ranges += if (start == i - 1) "$start" else "$start–${i - 1}"
                    start = -1
                }
            }
        }
        if (start >= 0) ranges += if (start == maxInclusive) "$start" else "$start–$maxInclusive"
        return ranges.joinToString(", ").ifEmpty { "none" }
    }

    private fun resetRunCounters() {
        seenFrames.fill(false)
        runStartedNs = 0L
        runEndedNs = 0L
        validFrames = 0
        innerFecFrames = 0
        uniqueFrames = 0
        innovativeBytes = 0
        observations = 0
        bitErrorsTotal = 0
        erasedBitsTotal = 0
        observedBitsTotal = 0
        analyzedFrames = 0
        firstAnalysisNs = 0L
        lastAnalysisNs = 0L
        pipelineMsTotal = 0.0
        pipelineWindow.fill(0.0)
        pipelineWindowCount = 0
        pipelineWindowCursor = 0
        failureCounts.clear()
        lastFailure = null
        captureWidth = 0
        captureHeight = 0
        lastAcquisitionMs = null
        lastSyncMs = null
        lastPayloadMs = null
    }

    private fun updateCapture(extra: Map<String, Any?>) {
        (extra["capture_width"] as? Number)?.toInt()?.let { captureWidth = it }
        (extra["capture_height"] as? Number)?.toInt()?.let { captureHeight = it }
        (extra["acquisition_ms"] as? Number)?.toDouble()?.let { lastAcquisitionMs = it }
        (extra["sync_ms"] as? Number)?.toDouble()?.let { lastSyncMs = it }
        (extra["payload_ms"] as? Number)?.toDouble()?.let { lastPayloadMs = it }
    }

    private fun addIndexes(json: JSONObject, key: String, values: IntArray?, count: Int) {
        if (values == null || count <= 0) return
        val array = JSONArray()
        for (index in 0 until count) array.put(values[index])
        json.put(key, array)
    }

    private fun putExtras(json: JSONObject, extra: Map<String, Any?>) {
        for ((key, value) in extra) json.put(key, jsonValue(value))
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
        is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
        is IntArray -> JSONArray().apply { value.forEach { put(it) } }
        is LongArray -> JSONArray().apply { value.forEach { put(it) } }
        is ShortArray -> JSONArray().apply { value.forEach { put(it.toInt()) } }
        is ByteArray -> JSONArray().apply { value.forEach { put(it.toInt() and 0xFF) } }
        is BooleanArray -> JSONArray().apply { value.forEach { put(it) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (nestedKey, nestedValue) ->
                if (nestedKey != null) put(nestedKey.toString(), jsonValue(nestedValue))
            }
        }
        else -> value.toString()
    }

    @Synchronized
    fun snapshot(nowNs: Long = System.nanoTime()): Phase1RunSnapshot {
        val elapsedEnd = if (runEndedNs > 0L) runEndedNs else nowNs
        val elapsed = if (runStartedNs > 0L) {
            (elapsedEnd - runStartedNs).coerceAtLeast(0L) / 1_000_000_000.0
        } else 0.0
        val cameraElapsed = if (firstAnalysisNs > 0L) {
            (lastAnalysisNs - firstAnalysisNs).coerceAtLeast(0L) / 1_000_000_000.0
        } else 0.0
        val sorted = pipelineWindow.copyOf(pipelineWindowCount).apply { sort() }
        val p95 = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size * 0.95).toInt()).coerceAtMost(sorted.lastIndex)]
        val nonErased = observedBitsTotal - erasedBitsTotal
        return Phase1RunSnapshot(
            campaignId,
            runId,
            runToken,
            senderState,
            syncStatus,
            geometryState,
            currentProfile?.name ?: "AUTO • searching",
            expectedFrames,
            analyzedFrames,
            observations,
            uniqueFrames,
            validFrames,
            innerFecFrames,
            innovativeBytes,
            elapsed,
            if (analyzedFrames > 0) pipelineMsTotal / analyzedFrames else 0.0,
            p95,
            if (cameraElapsed > 0.0) (analyzedFrames - 1) / cameraElapsed else 0.0,
            captureWidth,
            captureHeight,
            if (nonErased > 0) bitErrorsTotal.toDouble() / nonErased else 0.0,
            if (observedBitsTotal > 0) erasedBitsTotal.toDouble() / observedBitsTotal else 0.0,
            lastFailure,
            failureCounts.entries.sortedByDescending { it.value }.take(4)
                .joinToString(" • ") { "${it.key} ${it.value}" },
            lastAcquisitionMs,
            lastSyncMs,
            lastPayloadMs,
        )
    }

    internal fun jsonLinesForTest(): List<String> = synchronized(this) { lines.toList() }

    @Synchronized
    fun currentObservationLines(): List<String> = lines.toList()

    @Synchronized
    fun export(cacheDir: File): File {
        val file = File(cacheDir, "superqr-phase1-${campaignId.take(8)}.jsonl")
        file.bufferedWriter().use { writer ->
            for (line in lines) {
                writer.write(line)
                writer.newLine()
            }
        }
        return file
    }

    private companion object {
        const val POST_DONE_SEARCH_WINDOW_NS = 8_000_000_000L
    }
}
