package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.json.JSONObject


data class ShapeGridRunSnapshot(
    val profileName: String,
    val runToken: Int?,
    val senderState: String,
    val frameCount: Int,
    val observations: Int,
    val validBlockObservations: Int,
    val uniqueBlocks: Int,
    val innovativeBytes: Long,
    val elapsedSeconds: Double,
    val symbolErasures: Long,
    val shapeSymbolErrors: Long,
    val colorSymbolErrors: Long,
    val rsErrors: Long,
    val rsErasures: Long,
    val lastProjectedTilePitchPx: Double,
    val lastPipelineMs: Double,
    val lastFailure: String?,
) {
    val goodputKibS: Double get() = if (elapsedSeconds > 0.0) innovativeBytes / elapsedSeconds / 1024.0 else 0.0
    val goodputMbps: Double get() = if (elapsedSeconds > 0.0) innovativeBytes * 8.0 / elapsedSeconds / 1_000_000.0 else 0.0
    val blockYield: Double get() = if (frameCount > 0) uniqueBlocks.toDouble() / (8.0 * frameCount) else 0.0
}

/** Authoritative innovation accounting is by (run, frame, block), not whole frame. */
class ShapeGridObservationRecorder {
    private val lines = ArrayList<String>(16384)
    private var seen = BooleanArray(0)
    private var profileName = "NONE"
    private var runToken: Int? = null
    private var senderState = "UNKNOWN"
    private var frameCount = 256
    private var observations = 0
    private var validBlockObservations = 0
    private var uniqueBlocks = 0
    private var innovativeBytes = 0L
    private var symbolErasures = 0L
    private var shapeSymbolErrors = 0L
    private var colorSymbolErrors = 0L
    private var rsErrors = 0L
    private var rsErasures = 0L
    private var runStartedNs = 0L
    private var runEndedNs = 0L
    private var lastProjectedTilePitchPx = 0.0
    private var lastPipelineMs = 0.0
    private var lastFailure: String? = null

    val hasLines: Boolean @Synchronized get() = lines.isNotEmpty()

    @Synchronized
    fun reset() {
        lines.clear()
        resetRun()
        profileName = "NONE"
        runToken = null
        senderState = "UNKNOWN"
    }

    @Synchronized
    fun record(
        campaignId: String,
        result: ShapeGridResult,
        completedNs: Long,
        cameraFps: Double,
        captureWidth: Int,
        captureHeight: Int,
    ): ShapeGridRunSnapshot {
        val envelope = result.envelope
        if (runToken != envelope.runToken || profileName != result.profile.name) {
            resetRun()
            profileName = result.profile.name
            runToken = envelope.runToken
            frameCount = envelope.frameCount
            seen = BooleanArray(8 * frameCount)
        }
        senderState = envelope.state.name
        lastProjectedTilePitchPx = result.projectedTilePitchPx
        lastPipelineMs = result.shapegridTotalMs
        lastFailure = result.failure
        if (envelope.state == V7LabRunState.RUNNING && runStartedNs == 0L) runStartedNs = completedNs
        if (envelope.state == V7LabRunState.DONE && runStartedNs > 0L && runEndedNs == 0L) runEndedNs = completedNs

        for (observation in result.observations) {
            observations++
            symbolErasures += observation.symbolErasures
            shapeSymbolErrors += observation.shapeSymbolErrors
            colorSymbolErrors += observation.colorSymbolErrors
            rsErrors += observation.rsErrors
            rsErasures += observation.rsErasures
            if (observation.postFecValid) validBlockObservations++

            val key = observation.frameIndex * 8 + observation.blockId
            val novel = envelope.state == V7LabRunState.RUNNING &&
                observation.postFecValid && key in seen.indices && !seen[key]
            val useful = if (novel) observation.usefulBytes else 0
            if (novel) {
                seen[key] = true
                uniqueBlocks++
                innovativeBytes += useful
            }
            lines += JSONObject()
                .put("campaign_id", campaignId)
                .put("profile", result.profile.name)
                .put("profile_id", result.profile.id)
                .put("run_token", envelope.runToken)
                .put("run_id", "%04X".format(envelope.runToken))
                .put("sender_state", envelope.state.name)
                .put("frame_index", observation.frameIndex)
                .put("frame_count", envelope.frameCount)
                .put("block_id", observation.blockId)
                .put("block_count", 8)
                .put("target_fps", result.profile.targetFps)
                .put("receiver_max_fps", 30.0)
                .put("projected_tile_pitch_px", result.projectedTilePitchPx)
                .put("symbol_erasures", observation.symbolErasures)
                .put("shape_symbol_errors", observation.shapeSymbolErrors)
                .put("color_symbol_errors", observation.colorSymbolErrors)
                .put("rs_errors", observation.rsErrors)
                .put("rs_erasures", observation.rsErasures)
                .put("block_post_fec_valid", observation.postFecValid)
                .put("innovative_block_bytes", useful)
                .put("failure_reason", observation.failure ?: result.failure ?: JSONObject.NULL)
                .put("recovered_blocks", result.recoveredBlocks)
                .put("shapegrid_total_ms", result.shapegridTotalMs)
                .put("carrier_source", result.carrierSource)
                .put("camera_fps", cameraFps)
                .put("capture_width", captureWidth)
                .put("capture_height", captureHeight)
                .put("completed_ns", completedNs)
                .toString()
        }
        return snapshot(completedNs)
    }

    @Synchronized
    fun snapshot(nowNs: Long = System.nanoTime()): ShapeGridRunSnapshot {
        val end = if (runEndedNs > 0L) runEndedNs else nowNs
        val elapsed = if (runStartedNs > 0L) (end - runStartedNs).coerceAtLeast(0L) / 1_000_000_000.0 else 0.0
        return ShapeGridRunSnapshot(
            profileName = profileName,
            runToken = runToken,
            senderState = senderState,
            frameCount = frameCount,
            observations = observations,
            validBlockObservations = validBlockObservations,
            uniqueBlocks = uniqueBlocks,
            innovativeBytes = innovativeBytes,
            elapsedSeconds = elapsed,
            symbolErasures = symbolErasures,
            shapeSymbolErrors = shapeSymbolErrors,
            colorSymbolErrors = colorSymbolErrors,
            rsErrors = rsErrors,
            rsErasures = rsErasures,
            lastProjectedTilePitchPx = lastProjectedTilePitchPx,
            lastPipelineMs = lastPipelineMs,
            lastFailure = lastFailure,
        )
    }

    @Synchronized
    fun currentLines(): List<String> = lines.toList()

    private fun resetRun() {
        seen = BooleanArray(0)
        frameCount = 256
        observations = 0
        validBlockObservations = 0
        uniqueBlocks = 0
        innovativeBytes = 0L
        symbolErasures = 0L
        shapeSymbolErrors = 0L
        colorSymbolErrors = 0L
        rsErrors = 0L
        rsErasures = 0L
        runStartedNs = 0L
        runEndedNs = 0L
        lastProjectedTilePitchPx = 0.0
        lastPipelineMs = 0.0
        lastFailure = null
    }
}
