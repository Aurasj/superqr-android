package com.superqr.android.vision.v6.diagnostic

data class V6TransferStats(
    // ── SCANNING / ACQUISITION phase ──
    val analyzerFramesSinceReset: Int = 0,
    val acquisitionNs: Long = 0L,

    // ── ACTIVE TRANSFER phase ──
    val activeAnalyzerFrames: Int = 0,
    val detectorInvocations: Int = 0,
    val fullDetectionFrames: Int = 0,
    val trackedResampledFrames: Int = 0,
    val heldFrames: Int = 0,
    val borderFailures: Int = 0,
    val orientationFailures: Int = 0,

    // ── TRANSPORT outcomes (all counted on main executor) ──
    val crcValidFrames: Int = 0,
    val crcMismatchFrames: Int = 0,
    val uncertainFrames: Int = 0,
    val otherTransportRejectedFrames: Int = 0,

    // ── ACCUMULATOR outcomes ──
    val uniqueFramesAccepted: Int = 0,
    val duplicateFrames: Int = 0,
    val conflictFrames: Int = 0,
    val foreignSessionRejected: Int = 0,
    val inconsistentTotalFramesRejected: Int = 0,
    val totalLogicalFrames: Int = 0,

    // ── UNIQUE FRAME ACQUISITION LATENCY ──
    // Timestamps relative to ACTIVE start, computed on buildSnapshot().
    val acquisition25PctNs: Long = 0L,
    val acquisition50PctNs: Long = 0L,
    val acquisition75PctNs: Long = 0L,
    val acquisition90PctNs: Long = 0L,
    val acquisition100PctNs: Long = 0L,
    val acquisitionLongestGapNs: Long = 0L,
    val acquisitionLast5Ids: String = "",
    val acquisitionLast5TimesNs: LongArray = LongArray(0),
    val acquisitionMinHits: Int = 0,
    val acquisitionMaxHits: Int = 0,

    // ── TIMING ──
    val activeTransferNs: Long = 0L,

    // ── per-source timing aggregates (ns per invocation) ──
    val fullDetectionCount: Int = 0,
    val fullDetectionTotalNs: Long = 0L,
    val fullDetectionTimesNs: List<Long> = emptyList(),
    val trackedResampledCount: Int = 0,
    val trackedResampledTotalNs: Long = 0L,
    val trackedResampledTimesNs: List<Long> = emptyList(),

    // ── ImageProxy / callback timing (Active phase only) ──
    // Sensor timestamp deltas (imageProxy.imageInfo.timestamp).
    val sensorDeltaCount: Int = 0,
    val sensorDeltaTotalNs: Long = 0L,
    val sensorDeltasNs: List<Long> = emptyList(),

    // Analyzer callback arrival deltas (System.nanoTime).
    val callbackDeltaCount: Int = 0,
    val callbackDeltaTotalNs: Long = 0L,
    val callbackDeltasNs: List<Long> = emptyList(),

    // End-to-end analyzer occupancy (callback entry → imageProxy.close).
    val occupancyCount: Int = 0,
    val occupancyTotalNs: Long = 0L,
    val occupancyDurationsNs: List<Long> = emptyList(),

    // ── Coarse stage timing (Active phase only, ns) ──
    // Pre-detector: callback entry → just before detect()
    val preDetectorCount: Int = 0,
    val preDetectorTotalNs: Long = 0L,
    val preDetectorTimesNs: List<Long> = emptyList(),
    // Post-detector: detect() return → imageProxy.close()
    val postDetectorCount: Int = 0,
    val postDetectorTotalNs: Long = 0L,
    val postDetectorTimesNs: List<Long> = emptyList(),
    // Skipped deliveries: sensor Δ ≥ 50 ms (≈ 1.5 × 33.3ms nominal)
    val skippedDeliveryCount: Int = 0,

    // ── CAMERA CONFIG ──
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val cameraSupportedRanges: String = "",
    val cameraSelectedRange: String = "",
    val cameraBoundRange: String = "",
    val cameraFpsFallback: Boolean = false,

    // ── THROUGHPUT ──
    val completedFileBytes: Long = 0L
) {
    /** True when a high-FPS range was selected, bound successfully, and sensor cadence confirms it. */
    val cameraFpsHighSucceeded: Boolean
        get() = cameraSelectedRange.contains("60") && !cameraFpsFallback && sensorFps >= 50.0

    val acquisitionSeconds: Double
        get() = acquisitionNs.toDouble() / 1_000_000_000.0

    val acquisition25PctSeconds: Double
        get() = acquisition25PctNs.toDouble() / 1_000_000_000.0
    val acquisition50PctSeconds: Double
        get() = acquisition50PctNs.toDouble() / 1_000_000_000.0
    val acquisition75PctSeconds: Double
        get() = acquisition75PctNs.toDouble() / 1_000_000_000.0
    val acquisition90PctSeconds: Double
        get() = acquisition90PctNs.toDouble() / 1_000_000_000.0
    val acquisition100PctSeconds: Double
        get() = acquisition100PctNs.toDouble() / 1_000_000_000.0
    val acquisitionLongestGapSeconds: Double
        get() = acquisitionLongestGapNs.toDouble() / 1_000_000_000.0

    val activeTransferSeconds: Double
        get() = activeTransferNs.toDouble() / 1_000_000_000.0

    val effectiveFileBytesPerSecond: Double
        get() = if (activeTransferSeconds > 0.0)
            completedFileBytes.toDouble() / activeTransferSeconds else 0.0

    val acceptedTransportPayloadBytes: Long
        get() = uniqueFramesAccepted.toLong() * 91L

    val acceptedTransportPayloadBytesPerSecond: Double
        get() = if (activeTransferSeconds > 0.0)
            acceptedTransportPayloadBytes.toDouble() / activeTransferSeconds else 0.0

    val uniqueFramesPerSecond: Double
        get() = if (activeTransferSeconds > 0.0)
            uniqueFramesAccepted.toDouble() / activeTransferSeconds else 0.0

    val activeAnalyzerFps: Double
        get() = if (activeTransferSeconds > 0.0)
            activeAnalyzerFrames.toDouble() / activeTransferSeconds else 0.0

    val detectorFps: Double
        get() = if (activeTransferSeconds > 0.0)
            detectorInvocations.toDouble() / activeTransferSeconds else 0.0

    val fullDetectionMeanMs: Double
        get() = if (fullDetectionCount > 0) fullDetectionTotalNs.toDouble() / fullDetectionCount / 1_000_000.0 else 0.0
    val fullDetectionMedianMs: Double
        get() = percentileMs(fullDetectionTimesNs, 0.50)
    val fullDetectionP95Ms: Double
        get() = percentileMs(fullDetectionTimesNs, 0.95)

    val trackedResampledMeanMs: Double
        get() = if (trackedResampledCount > 0) trackedResampledTotalNs.toDouble() / trackedResampledCount / 1_000_000.0 else 0.0
    val trackedResampledMedianMs: Double
        get() = percentileMs(trackedResampledTimesNs, 0.50)
    val trackedResampledP95Ms: Double
        get() = percentileMs(trackedResampledTimesNs, 0.95)

    val sensorDeltaMeanMs: Double
        get() = ratioMs(sensorDeltaTotalNs, sensorDeltaCount)
    val sensorDeltaMedianMs: Double
        get() = percentileMs(sensorDeltasNs, 0.50)
    val sensorDeltaP95Ms: Double
        get() = percentileMs(sensorDeltasNs, 0.95)
    val sensorDeltaMinMs: Double
        get() = if (sensorDeltasNs.isEmpty()) 0.0 else sensorDeltasNs.min().toDouble() / 1_000_000.0
    val sensorFps: Double
        get() = if (sensorDeltaMeanMs > 0.0) 1000.0 / sensorDeltaMeanMs else 0.0

    val callbackDeltaMeanMs: Double
        get() = ratioMs(callbackDeltaTotalNs, callbackDeltaCount)
    val callbackDeltaMedianMs: Double
        get() = percentileMs(callbackDeltasNs, 0.50)
    val callbackDeltaP95Ms: Double
        get() = percentileMs(callbackDeltasNs, 0.95)
    val callbackDeltaMinMs: Double
        get() = if (callbackDeltasNs.isEmpty()) 0.0 else callbackDeltasNs.min().toDouble() / 1_000_000.0
    val callbackFps: Double
        get() = if (callbackDeltaMeanMs > 0.0) 1000.0 / callbackDeltaMeanMs else 0.0

    val occupancyMeanMs: Double
        get() = ratioMs(occupancyTotalNs, occupancyCount)
    val occupancyMedianMs: Double
        get() = percentileMs(occupancyDurationsNs, 0.50)
    val occupancyP95Ms: Double
        get() = percentileMs(occupancyDurationsNs, 0.95)

    val preDetectorMeanMs: Double
        get() = ratioMs(preDetectorTotalNs, preDetectorCount)
    val postDetectorMeanMs: Double
        get() = ratioMs(postDetectorTotalNs, postDetectorCount)

    val skippedDeliveryRatio: Double
        get() = if (sensorDeltaCount > 0) skippedDeliveryCount.toDouble() / sensorDeltaCount else 0.0

    private fun ratioMs(totalNs: Long, count: Int) =
        if (count > 0) totalNs.toDouble() / count / 1_000_000.0 else 0.0

    private fun percentileMs(times: List<Long>, p: Double): Double {
        if (times.isEmpty()) return 0.0
        val sorted = times.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx].toDouble() / 1_000_000.0
    }

    fun formatSummary(): String = buildString {
        appendLine("── SUPERQR V6 TRANSFER STATS ──")
        appendLine("Acquisition:       ${"%.2f".format(acquisitionSeconds)} s")
        appendLine("Active transfer:   ${"%.2f".format(activeTransferSeconds)} s")
        appendLine()
        appendLine("── CAMERA CONFIG ──")
        appendLine("Analysis:          ${analysisWidth}x$analysisHeight px")
        appendLine("Supported ranges:  ${cameraSupportedRanges.ifEmpty { "-" }}")
        appendLine("Selected:          ${cameraSelectedRange.ifEmpty { "DEFAULT" }}")
        appendLine("Bound:             ${cameraBoundRange.ifEmpty { "DEFAULT" }}")
        appendLine("Fallback:          ${cameraFpsFallback}")
        appendLine("High-fps success:  ${cameraFpsHighSucceeded}")
        appendLine("Delivery fps:      ${"%.1f".format(sensorFps)}")
        appendLine("Skipped delivery:  $skippedDeliveryCount / $sensorDeltaCount (${"%.1f".format(skippedDeliveryRatio * 100.0)}%)")
        appendLine()
        appendLine("── CAMERA / ANALYZER CADENCE ──")
        appendLine("Sensor timestamps: ${"%.1f".format(sensorDeltaMeanMs)} mean  ${"%.1f".format(sensorDeltaMedianMs)} p50  ${"%.1f".format(sensorDeltaP95Ms)} p95  ${"%.1f".format(sensorDeltaMinMs)} min ms  → ${"%.1f".format(sensorFps)} fps")
        appendLine("Callback arrivals: ${"%.1f".format(callbackDeltaMeanMs)} mean  ${"%.1f".format(callbackDeltaMedianMs)} p50  ${"%.1f".format(callbackDeltaP95Ms)} p95  ${"%.1f".format(callbackDeltaMinMs)} min ms  → ${"%.1f".format(callbackFps)} fps")
        appendLine("Occupancy:         ${"%.1f".format(occupancyMeanMs)} mean  ${"%.1f".format(occupancyMedianMs)} p50  ${"%.1f".format(occupancyP95Ms)} p95 ms")
        appendLine("  Pre-detector:    ${"%.1f".format(preDetectorMeanMs)} ms")
        appendLine("  Post-detector:   ${"%.1f".format(postDetectorMeanMs)} ms")
        appendLine()
        appendLine("── DETECTOR ──")
        appendLine("Analyzer fps:      ${"%.1f".format(activeAnalyzerFps)}")
        appendLine("Detector fps:      ${"%.1f".format(detectorFps)}")
        appendLine("Analyzer frames:   $analyzerFramesSinceReset scan + $activeAnalyzerFrames active")
        appendLine("Detector calls:    $detectorInvocations")
        appendLine("FULL: $fullDetectionFrames  mean ${"%.1f".format(fullDetectionMeanMs)}  p50 ${"%.1f".format(fullDetectionMedianMs)}  p95 ${"%.1f".format(fullDetectionP95Ms)} ms")
        appendLine("TRACKED: $trackedResampledFrames  mean ${"%.1f".format(trackedResampledMeanMs)}  p50 ${"%.1f".format(trackedResampledMedianMs)}  p95 ${"%.1f".format(trackedResampledP95Ms)} ms")
        appendLine()
        appendLine("── TRANSPORT ──")
        appendLine("CRC valid:  $crcValidFrames")
        appendLine("CRC mismatch:  $crcMismatchFrames")
        appendLine("Uncertain:  $uncertainFrames")
        appendLine()
        appendLine("── ACCUMULATOR ──")
        appendLine("Unique:    $uniqueFramesAccepted / $totalLogicalFrames")
        appendLine("Duplicates: $duplicateFrames")
        appendLine("Conflicts:  $conflictFrames")
        appendLine()
        appendLine("── UNIQUE ACQUISITION ──")
        appendLine("25%: ${"%.2f".format(acquisition25PctSeconds)} s")
        appendLine("50%: ${"%.2f".format(acquisition50PctSeconds)} s")
        appendLine("75%: ${"%.2f".format(acquisition75PctSeconds)} s")
        appendLine("90%: ${"%.2f".format(acquisition90PctSeconds)} s")
        appendLine("100%: ${"%.2f".format(acquisition100PctSeconds)} s")
        appendLine("Longest gap: ${"%.2f".format(acquisitionLongestGapSeconds)} s")
        appendLine("Last unique IDs: $acquisitionLast5Ids")
        if (acquisitionLast5TimesNs.isNotEmpty()) {
            appendLine("Last unique at:  ${acquisitionLast5TimesNs.joinToString("  ") { "%.2f".format(it.toDouble() / 1_000_000_000.0) }} s")
        }
        appendLine("Frame hits: $acquisitionMinHits min  $acquisitionMaxHits max")
        appendLine()
        appendLine("── THROUGHPUT ──")
        appendLine("File:        $completedFileBytes bytes")
        appendLine("User thrpt:  ${"%.2f".format(effectiveFileBytesPerSecond / 1024.0)} KB/s")
        appendLine("Trans thrpt: ${"%.2f".format(acceptedTransportPayloadBytesPerSecond / 1024.0)} KB/s")
        appendLine("Frame rate:  ${"%.1f".format(uniqueFramesPerSecond)} fps")
    }
}
