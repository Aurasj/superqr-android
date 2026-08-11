package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.json.JSONObject

data class AdvancedRunSnapshot(
    val profileName: String,
    val runToken: Int?,
    val senderState: String,
    val laneCount: Int,
    val frameCount: Int,
    val observations: Int,
    val uniqueLaneFrames: Int,
    val innovativeBytes: Long,
    val elapsedSeconds: Double,
    val bitErrorRate: Double,
    val erasureRate: Double,
    val decodedQrLanesLastFrame: Int,
    val lastFailure: String?,
) {
    val goodputKibS: Double get() = if (elapsedSeconds > 0.0) innovativeBytes / elapsedSeconds / 1024.0 else 0.0
    val progress: Double get() = if (laneCount > 0 && frameCount > 0) {
        uniqueLaneFrames.toDouble() / (laneCount * frameCount).toDouble()
    } else 0.0
}

/** Counts innovation by (lane, frame), never by display epoch alone. */
class AdvancedObservationRecorder {
    private val lines = ArrayList<String>(8192)
    private var seen = BooleanArray(0)
    private var profileName = "NONE"
    private var runToken: Int? = null
    private var senderState = "UNKNOWN"
    private var laneCount = 0
    private var frameCount = 0
    private var observations = 0
    private var uniqueLaneFrames = 0
    private var innovativeBytes = 0L
    private var observedBits = 0L
    private var bitErrors = 0L
    private var erasedBits = 0L
    private var runStartedNs = 0L
    private var runEndedNs = 0L
    private var decodedQrLanesLastFrame = 0
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
        result: AdvancedPhyResult,
        completedNs: Long,
        cameraFps: Double,
        captureWidth: Int,
        captureHeight: Int,
    ): AdvancedRunSnapshot {
        val envelope = result.envelope
        if (runToken != envelope.runToken || profileName != result.profile.name) {
            resetRun()
            profileName = result.profile.name
            runToken = envelope.runToken
            laneCount = result.profile.laneCount
            frameCount = envelope.frameCount
            require(laneCount > 0) { "advanced lane count must be positive" }
            require(frameCount > 0) { "advanced frame count must be positive" }
            seen = BooleanArray(laneCount * frameCount)
        }
        senderState = envelope.state.name
        decodedQrLanesLastFrame = result.decodedQrLanes
        lastFailure = result.failure
        if (envelope.state == V7LabRunState.RUNNING && runStartedNs == 0L) runStartedNs = completedNs
        if (envelope.state == V7LabRunState.DONE && runStartedNs > 0L && runEndedNs == 0L) runEndedNs = completedNs

        for (observation in result.observations) {
            observations++
            observedBits += observation.observedBits
            bitErrors += observation.bitErrors
            erasedBits += observation.erasedBits
            val laneValid = observation.laneId in 0 until laneCount
            val frameValid = observation.frameIndex in 0 until frameCount
            val key = if (laneValid && frameValid) observation.laneId * frameCount + observation.frameIndex else -1
            val novel = observation.postFecValid && key >= 0 && !seen[key]
            val useful = if (novel) observation.usefulBytes else 0
            if (novel) {
                seen[key] = true
                uniqueLaneFrames++
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
                .put("lane_id", observation.laneId)
                .put("lane_kind", observation.kind)
                .put("lane_count", result.profile.laneCount)
                .put("target_fps", result.profile.targetFps)
                .put("receiver_max_fps", 30.0)
                .put("observed_bits", observation.observedBits)
                .put("bit_errors", observation.bitErrors)
                .put("erased_bits", observation.erasedBits)
                .put("frame_valid", observation.frameValid)
                .put("post_fec_valid", observation.postFecValid)
                .put("innovative_bytes", useful)
                .put("valid_samples", observation.validSamples)
                .put("failure_reason", observation.failure ?: result.failure ?: JSONObject.NULL)
                .put("decoded_qr_lanes", result.decodedQrLanes)
                .put("carrier_source", result.carrierSource ?: JSONObject.NULL)
                .put("pipeline_ms", result.pipelineMs)
                .put("camera_fps", cameraFps)
                .put("capture_width", captureWidth)
                .put("capture_height", captureHeight)
                .put("completed_ns", completedNs)
                .toString()
        }
        return snapshot(completedNs)
    }

    @Synchronized
    fun snapshot(nowNs: Long = System.nanoTime()): AdvancedRunSnapshot {
        val end = if (runEndedNs > 0L) runEndedNs else nowNs
        val elapsed = if (runStartedNs > 0L) (end - runStartedNs).coerceAtLeast(0L) / 1_000_000_000.0 else 0.0
        val ber = if (observedBits > 0L) bitErrors.toDouble() / observedBits else 0.0
        val erasure = if (observedBits > 0L) erasedBits.toDouble() / observedBits else 0.0
        return AdvancedRunSnapshot(
            profileName = profileName,
            runToken = runToken,
            senderState = senderState,
            laneCount = laneCount,
            frameCount = frameCount,
            observations = observations,
            uniqueLaneFrames = uniqueLaneFrames,
            innovativeBytes = innovativeBytes,
            elapsedSeconds = elapsed,
            bitErrorRate = ber,
            erasureRate = erasure,
            decodedQrLanesLastFrame = decodedQrLanesLastFrame,
            lastFailure = lastFailure,
        )
    }

    @Synchronized
    fun currentLines(): List<String> = lines.toList()

    private fun resetRun() {
        seen = BooleanArray(0)
        laneCount = 0
        frameCount = 0
        observations = 0
        uniqueLaneFrames = 0
        innovativeBytes = 0L
        observedBits = 0L
        bitErrors = 0L
        erasedBits = 0L
        runStartedNs = 0L
        runEndedNs = 0L
        decodedQrLanesLastFrame = 0
        lastFailure = null
    }
}
