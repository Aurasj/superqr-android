package com.superqr.android.ui.scanner

import com.superqr.android.camera.V7MeasurementTracker
import com.superqr.android.vision.v7.transport.V7OpticalProfile
import com.superqr.android.vision.v7.transport.V7TransportDiagnostics
import com.superqr.android.vision.v7.transport.V7TransportFrame
import java.util.ArrayDeque

/** One compact analysis event for post-mortem optical + performance debugging. */
data class V7DebugEvent(
    val analysisIndex: Int,
    val profileId: Int,
    val profileKey: String,
    val sessionId: Int?,
    val frameId: Int?,
    val totalFrames: Int?,
    val payloadLen: Int?,
    val cameraDeliveredFps: Double,
    val analysisFps: Double,
    val pipelineMs: Double,
    val detectorMs: Double,
    val v7TotalMs: Double,
    val v7ProfileMs: Double,
    val v7SamplingMs: Double,
    val v7ClassificationMs: Double,
    val v7TransportMs: Double,
    val usefulUniqueFps: Double,
    val decodedPayloadKiBs: Double,
    val rawErasures: Int,
    val remainingErasures: Int,
    val temporalObservations: Int,
    val temporalFilled: Int,
    val temporalOverridden: Int,
    val headerValid: Boolean,
    val headerError: String?,
    val crcCandidateAttempts: Int,
    val crcPassed: Boolean,
    val candidatePassed: String?,
    val receivedCrc32: Long?,
    val computedCrc32: Long?,
    val acceptedFrameId: Int?,
    val rejectionReason: String?,
)

data class V7DebugFrameSummary(
    val profileId: Int,
    val sessionId: Int,
    val frameId: Int,
    val totalFrames: Int,
    val observations: Int,
    val crcPass: Int,
    val crcFail: Int,
    val headerOnly: Int,
    val minRawErasures: Int,
    val maxRawErasures: Int,
    val lastCandidatePassed: String?,
)

/** Thread-safe bounded history kept outside Compose state. */
class V7DebugHistory(private val capacity: Int = 512) {
    private val events = ArrayDeque<V7DebugEvent>(capacity)

    @Synchronized
    fun reset() {
        events.clear()
    }

    @Synchronized
    fun record(
        analysisIndex: Int,
        profile: V7OpticalProfile,
        transport: V7TransportDiagnostics,
        accepted: V7TransportFrame?,
        measurement: V7MeasurementTracker.Snapshot,
        cameraDeliveredFps: Double,
    ) {
        val h = transport.header
        if (events.size >= capacity) events.removeFirst()
        events.addLast(
            V7DebugEvent(
                analysisIndex = analysisIndex,
                profileId = profile.id,
                profileKey = profile.key,
                sessionId = h?.sessionId,
                frameId = h?.frameId,
                totalFrames = h?.totalFrames,
                payloadLen = h?.payloadLen,
                cameraDeliveredFps = cameraDeliveredFps,
                analysisFps = measurement.analysisFps,
                pipelineMs = measurement.pipelineMs,
                detectorMs = measurement.detectorMs,
                v7TotalMs = measurement.v7TotalMs,
                v7ProfileMs = measurement.v7ProfileMs,
                v7SamplingMs = measurement.v7SamplingMs,
                v7ClassificationMs = measurement.v7ClassificationMs,
                v7TransportMs = measurement.v7TransportMs,
                usefulUniqueFps = measurement.usefulUniqueFps,
                decodedPayloadKiBs = measurement.decodedPayloadKiBs,
                rawErasures = transport.rawErasures,
                remainingErasures = transport.remainingErasures,
                temporalObservations = transport.temporalObservations,
                temporalFilled = transport.temporalFilledCells,
                temporalOverridden = transport.temporalOverriddenCells,
                headerValid = transport.headerValid,
                headerError = transport.headerError,
                crcCandidateAttempts = transport.crcCandidateAttempts,
                crcPassed = transport.crcPassed,
                candidatePassed = transport.candidatePassed,
                receivedCrc32 = transport.receivedCrc32,
                computedCrc32 = transport.computedCrc32,
                acceptedFrameId = accepted?.frameId,
                rejectionReason = transport.rejectionReason,
            )
        )
    }

    @Synchronized
    fun snapshot(): List<V7DebugEvent> = events.toList()

    @Synchronized
    fun summaries(): List<V7DebugFrameSummary> {
        data class MutableSummary(
            var observations: Int = 0,
            var crcPass: Int = 0,
            var crcFail: Int = 0,
            var headerOnly: Int = 0,
            var minRawErasures: Int = Int.MAX_VALUE,
            var maxRawErasures: Int = 0,
            var lastCandidatePassed: String? = null,
        )

        val grouped = linkedMapOf<String, Pair<V7DebugEvent, MutableSummary>>()
        for (event in events) {
            val session = event.sessionId ?: continue
            val frame = event.frameId ?: continue
            val total = event.totalFrames ?: continue
            val key = "${event.profileId}:$session:$frame:$total"
            val pair = grouped.getOrPut(key) { event to MutableSummary() }
            val s = pair.second
            s.observations++
            if (event.crcPassed) s.crcPass++
            else if (event.crcCandidateAttempts > 0) s.crcFail++
            else s.headerOnly++
            s.minRawErasures = minOf(s.minRawErasures, event.rawErasures)
            s.maxRawErasures = maxOf(s.maxRawErasures, event.rawErasures)
            if (event.candidatePassed != null) s.lastCandidatePassed = event.candidatePassed
        }
        return grouped.values.map { (event, s) ->
            V7DebugFrameSummary(
                profileId = event.profileId,
                sessionId = event.sessionId!!,
                frameId = event.frameId!!,
                totalFrames = event.totalFrames!!,
                observations = s.observations,
                crcPass = s.crcPass,
                crcFail = s.crcFail,
                headerOnly = s.headerOnly,
                minRawErasures = if (s.minRawErasures == Int.MAX_VALUE) 0 else s.minRawErasures,
                maxRawErasures = s.maxRawErasures,
                lastCandidatePassed = s.lastCandidatePassed,
            )
        }.sortedWith(compareBy<V7DebugFrameSummary> { it.sessionId }.thenBy { it.frameId })
    }
}
