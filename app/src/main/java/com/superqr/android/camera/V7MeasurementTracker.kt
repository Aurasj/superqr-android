package com.superqr.android.camera

import java.util.UUID

/** Canonical V7.0 receiver-side measurement accumulator. */
class V7MeasurementTracker {
    data class Snapshot(
        val schemaVersion: Int,
        val runId: String,
        val sessionElapsedMs: Double,
        val analysisFps: Double,
        val pipelineMs: Double,
        val detectorMs: Double,
        val v7TotalMs: Double,
        val v7ProfileMs: Double,
        val v7SamplingMs: Double,
        val v7ClassificationMs: Double,
        val v7TransportMs: Double,
        val usefulUniqueFps: Double,
        val acceptedPayloadBytes: Long,
        val decodedPayloadKiBs: Double,
        val analysisCompleted: Int,
        val analysisExceptionCount: Int,
        val lastAnalysisException: String?,
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }

    private val analysisRate = V7AnalysisRateAccumulator(64)
    private val acceptedFrameIds = HashSet<Int>()
    private var runId = ""
    private var startNs = 0L
    private var currentAcceptedSession = -1
    private var acceptedPayloadBytes = 0L
    private var analysisCompleted = 0
    private var analysisExceptionCount = 0
    private var lastAnalysisException: String? = null
    private var pipelineMs = 0.0
    private var detectorMs = 0.0
    private var v7TotalMs = 0.0
    private var v7ProfileMs = 0.0
    private var v7SamplingMs = 0.0
    private var v7ClassificationMs = 0.0
    private var v7TransportMs = 0.0

    init { reset() }

    fun reset(nowNs: Long = System.nanoTime()) {
        runId = UUID.randomUUID().toString().replace("-", "")
        startNs = nowNs
        currentAcceptedSession = -1
        acceptedFrameIds.clear()
        acceptedPayloadBytes = 0L
        analysisCompleted = 0
        analysisExceptionCount = 0
        lastAnalysisException = null
        pipelineMs = 0.0
        detectorMs = 0.0
        v7TotalMs = 0.0
        v7ProfileMs = 0.0
        v7SamplingMs = 0.0
        v7ClassificationMs = 0.0
        v7TransportMs = 0.0
        analysisRate.reset()
    }

    fun recordAnalysis(
        completedNs: Long,
        pipelineMs: Double,
        detectorMs: Double,
        v7TotalMs: Double,
        v7ProfileMs: Double,
        v7SamplingMs: Double,
        v7ClassificationMs: Double,
        v7TransportMs: Double,
    ) {
        analysisCompleted++
        analysisRate.recordCompletion(completedNs)
        this.pipelineMs = pipelineMs.coerceAtLeast(0.0)
        this.detectorMs = detectorMs.coerceAtLeast(0.0)
        this.v7TotalMs = v7TotalMs.coerceAtLeast(0.0)
        this.v7ProfileMs = v7ProfileMs.coerceAtLeast(0.0)
        this.v7SamplingMs = v7SamplingMs.coerceAtLeast(0.0)
        this.v7ClassificationMs = v7ClassificationMs.coerceAtLeast(0.0)
        this.v7TransportMs = v7TransportMs.coerceAtLeast(0.0)
    }

    /** Returns true only for a new CRC-valid logical frame in this run/session. */
    fun recordAccepted(sessionId: Int, frameId: Int, payloadBytes: Int): Boolean {
        if (currentAcceptedSession != sessionId) {
            currentAcceptedSession = sessionId
            acceptedFrameIds.clear()
            acceptedPayloadBytes = 0L
        }
        val unique = acceptedFrameIds.add(frameId)
        if (unique) acceptedPayloadBytes += payloadBytes.coerceAtLeast(0).toLong()
        return unique
    }

    fun recordException(t: Throwable) {
        analysisExceptionCount++
        lastAnalysisException = "${t::class.java.simpleName}: ${t.message ?: "no message"}"
    }

    fun snapshot(nowNs: Long = System.nanoTime()): Snapshot {
        val elapsedSec = ((nowNs - startNs).coerceAtLeast(0L)) / 1_000_000_000.0
        val usefulFps = if (elapsedSec > 0.0) acceptedFrameIds.size / elapsedSec else 0.0
        val decodedKiBs = if (elapsedSec > 0.0) acceptedPayloadBytes / 1024.0 / elapsedSec else 0.0
        return Snapshot(
            schemaVersion = SCHEMA_VERSION,
            runId = runId,
            sessionElapsedMs = elapsedSec * 1000.0,
            analysisFps = analysisRate.computeFps(nowNs),
            pipelineMs = pipelineMs,
            detectorMs = detectorMs,
            v7TotalMs = v7TotalMs,
            v7ProfileMs = v7ProfileMs,
            v7SamplingMs = v7SamplingMs,
            v7ClassificationMs = v7ClassificationMs,
            v7TransportMs = v7TransportMs,
            usefulUniqueFps = usefulFps,
            acceptedPayloadBytes = acceptedPayloadBytes,
            decodedPayloadKiBs = decodedKiBs,
            analysisCompleted = analysisCompleted,
            analysisExceptionCount = analysisExceptionCount,
            lastAnalysisException = lastAnalysisException,
        )
    }
}
