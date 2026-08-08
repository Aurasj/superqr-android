package com.superqr.android.vision.v6.diagnostic

import com.superqr.android.vision.v6.transport.V6TransferPackage
import com.superqr.android.vision.v6.transport.V6TransportFrame

/**
 * Passive observer that accumulates per-transfer statistics without modifying
 * V6SessionAccumulator semantics.
 *
 * Lifecycle:
 *   SCANNING  – collector created or reset; counting analyzer frames until
 *               the first unique transport frame is accepted.
 *   ACTIVE    – at least one unique transport frame has been accepted.
 *   COMPLETE  – V6TransferPackage completed; stats are frozen.
 *
 * All public methods are synchronized so the object is safe to use from
 * both the analysis executor and the main executor.
 */
class V6TransferStatsCollector {

    enum class State { SCANNING, ACTIVE, COMPLETE }

    @Volatile var state: State = State.SCANNING
        private set

    private var camConfig = CameraConfig()

    private data class CameraConfig(
        val width: Int = 0,
        val height: Int = 0,
        val supportedRanges: String = "",
        val selectedRange: String = "",
        val boundRange: String = "",
        val fallback: Boolean = false
    )

    private var stats = V6TransferStats()
    private var collectorStartNs: Long = 0L
    private var activeStartNs: Long = 0L
    private val fullTimings = mutableListOf<Long>()
    private val trackedTimings = mutableListOf<Long>()

    private val sensorDeltas = mutableListOf<Long>()
    private val callbackDeltas = mutableListOf<Long>()
    private val occupancyDurations = mutableListOf<Long>()
    private val preDetectorTimings = mutableListOf<Long>()
    private val postDetectorTimings = mutableListOf<Long>()

    // ── per-frame-ID acquisition latency tracking ──
    private var firstAcceptNs: LongArray? = null
    private var validHits: IntArray? = null
    private var uniqueAcceptOrder = mutableListOf<Int>()
    private var prevSensorTimestampNs: Long = -1L
    private var prevCallbackArrivalNs: Long = -1L
    private var activeCallbackStartNs: Long = -1L
    private var activeDetectorEndNs: Long = 0L

    // Buffered last detector result for SCANNING→ACTIVE replay.
    private var lastResultSource: String? = null
    private var lastResultBorderFound: Boolean = false
    private var lastResultOrientationResolved: Boolean = false
    private var lastResultDetectorStartNs: Long = 0L
    private var lastResultDetectorEndNs: Long = 0L
    private var lastResultCrcValid: Boolean = false
    private var lastResultCrcError: String? = null

    /** Resets the collector for a new transfer attempt. */
    @Synchronized
    fun reset() {
        stats = V6TransferStats()
        state = State.SCANNING
        collectorStartNs = System.nanoTime()
        activeStartNs = 0L
        lastResultSource = null
        lastResultBorderFound = false
        lastResultOrientationResolved = false
        lastResultDetectorStartNs = 0L
        lastResultDetectorEndNs = 0L
        lastResultCrcValid = false
        lastResultCrcError = null
        fullTimings.clear(); trackedTimings.clear()
        sensorDeltas.clear(); callbackDeltas.clear(); occupancyDurations.clear()
        preDetectorTimings.clear(); postDetectorTimings.clear()
        firstAcceptNs = null; validHits = null; uniqueAcceptOrder.clear()
        prevSensorTimestampNs = -1L
        prevCallbackArrivalNs = -1L
        activeCallbackStartNs = -1L
        activeDetectorEndNs = 0L
    }

    /**
     * Called once after camera binding to record the camera/use-case configuration.
     * Camera config survives transfer-stat resets — it is session-level state.
     */
    @Synchronized
    fun recordCameraConfig(
        width: Int,
        height: Int,
        supportedRanges: String = "",
        selectedRange: String = "",
        boundRange: String = "",
        fallback: Boolean = false
    ) {
        // Merge semantics: each field updates only when the new value carries
        // information. Empty strings / zero ints are not meaningful updates.
        val w = if (width > 0) width else camConfig.width
        val h = if (height > 0) height else camConfig.height
        val sr = if (supportedRanges.isNotBlank()) supportedRanges else camConfig.supportedRanges
        val sl = if (selectedRange.isNotBlank()) selectedRange else camConfig.selectedRange
        val br = if (boundRange.isNotBlank()) boundRange else camConfig.boundRange
        camConfig = CameraConfig(w, h, sr, sl, br, fallback)
        stats = stats.copy(
            analysisWidth = w,
            analysisHeight = h,
            cameraSupportedRanges = supportedRanges,
            cameraSelectedRange = selectedRange,
            cameraBoundRange = boundRange,
            cameraFpsFallback = fallback
        )
    }

    /**
     * Called for every ImageProxy that enters the analysis callback.
     * @param sensorTimestampNs  imageProxy.imageInfo.timestamp (ns), or 0 if unavailable
     */
    @Synchronized
    fun recordAnalyzerFrame(sensorTimestampNs: Long = 0L) {
        if (state == State.COMPLETE) return
        val now = System.nanoTime()

        stats = when (state) {
            State.SCANNING -> stats.copy(analyzerFramesSinceReset = stats.analyzerFramesSinceReset + 1)
            State.ACTIVE -> {
                // Sensor timestamp delta (monotonic timebase, relative only).
                if (sensorTimestampNs > 0 && prevSensorTimestampNs > 0) {
                    val delta = sensorTimestampNs - prevSensorTimestampNs
                    if (delta > 0) {
                        val skipped = if (delta >= 50_000_000L) 1 else 0
                        stats = stats.copy(
                            sensorDeltaCount = stats.sensorDeltaCount + 1,
                            sensorDeltaTotalNs = stats.sensorDeltaTotalNs + delta,
                            skippedDeliveryCount = stats.skippedDeliveryCount + skipped
                        )
                        sensorDeltas.add(delta)
                    }
                }
                if (sensorTimestampNs > 0) prevSensorTimestampNs = sensorTimestampNs

                // Callback arrival delta.
                if (prevCallbackArrivalNs > 0) {
                    val delta = now - prevCallbackArrivalNs
                    if (delta > 0) {
                        stats = stats.copy(
                            callbackDeltaCount = stats.callbackDeltaCount + 1,
                            callbackDeltaTotalNs = stats.callbackDeltaTotalNs + delta
                        )
                        callbackDeltas.add(delta)
                    }
                }
                prevCallbackArrivalNs = now

                // Start occupancy timer for this frame.
                activeCallbackStartNs = now

                stats.copy(activeAnalyzerFrames = stats.activeAnalyzerFrames + 1)
            }
            else -> stats
        }
    }

    /**
     * Called immediately after imageProxy.close() if an active-frame occupancy
     * measurement was started. Pair with recordAnalyzerFrame.
     */
    @Synchronized
    fun recordAnalyzerClose() {
        if (state != State.ACTIVE) return
        val now = System.nanoTime()
        // Post-detector: detect() return → imageProxy.close()
        val detEnd = activeDetectorEndNs
        if (detEnd > 0) {
            val postNs = now - detEnd
            if (postNs > 0) {
                stats = stats.copy(
                    postDetectorCount = stats.postDetectorCount + 1,
                    postDetectorTotalNs = stats.postDetectorTotalNs + postNs
                )
                postDetectorTimings.add(postNs)
            }
        }
        // Total occupancy: callback entry → imageProxy.close()
        val start = activeCallbackStartNs
        if (start > 0) {
            val elapsed = now - start
            if (elapsed > 0) {
                stats = stats.copy(
                    occupancyCount = stats.occupancyCount + 1,
                    occupancyTotalNs = stats.occupancyTotalNs + elapsed
                )
                occupancyDurations.add(elapsed)
            }
            activeCallbackStartNs = -1L
        }
    }

    /** Records pre-detector time: callback → detect() entry. */
    @Synchronized
    fun recordStageTiming(preDetectorNs: Long) {
        if (state != State.ACTIVE || preDetectorNs <= 0) return
        stats = stats.copy(
            preDetectorCount = stats.preDetectorCount + 1,
            preDetectorTotalNs = stats.preDetectorTotalNs + preDetectorNs
        )
        preDetectorTimings.add(preDetectorNs)
    }

    /**
     * Called after the detector returns. Records classification source,
     * geometry failures, and timing. CRC outcomes are NOT counted here —
     * they are counted on the main executor via recordCrcOutcome.
     */
    @Synchronized
    fun recordDetectorResult(
        classificationSource: String?,
        borderFound: Boolean,
        orientationResolved: Boolean,
        detectorStartNs: Long = 0L,
        detectorEndNs: Long = 0L
    ) {
        if (state == State.COMPLETE) return

        lastResultSource = classificationSource
        lastResultBorderFound = borderFound
        lastResultOrientationResolved = orientationResolved
        lastResultDetectorStartNs = detectorStartNs
        lastResultDetectorEndNs = detectorEndNs

        if (state == State.ACTIVE) {
            activeDetectorEndNs = detectorEndNs
            accumulateDetectorResult(
                classificationSource, borderFound, orientationResolved,
                detectorStartNs, detectorEndNs
            )
        }
    }

    /**
     * Called on the MAIN EXECUTOR for every analyzed frame to record the
     * CRC/transport outcome. This eliminates the analysis↔main race that
     * caused the ±1 CRC discrepancy.
     */
    @Synchronized
    fun recordCrcOutcome(
        transportFrame: Any?,
        transportError: String?
    ) {
        if (state == State.COMPLETE) return

        lastResultCrcValid = transportFrame != null
        lastResultCrcError = transportError

        if (state == State.ACTIVE) {
            accumulateCrc(transportFrame, transportError)
        }
    }

    private fun accumulateDetectorResult(
        classificationSource: String?,
        borderFound: Boolean,
        orientationResolved: Boolean,
        detectorStartNs: Long,
        detectorEndNs: Long
    ) {
        val elapsedNs = if (detectorStartNs > 0 && detectorEndNs > detectorStartNs)
            detectorEndNs - detectorStartNs else 0L

        stats = stats.copy(detectorInvocations = stats.detectorInvocations + 1)

        if (!borderFound) {
            stats = stats.copy(borderFailures = stats.borderFailures + 1)
        } else if (!orientationResolved) {
            stats = stats.copy(orientationFailures = stats.orientationFailures + 1)
        }

        when (classificationSource) {
            "FULL_DETECTION" -> {
                stats = stats.copy(
                    fullDetectionFrames = stats.fullDetectionFrames + 1,
                    fullDetectionCount = stats.fullDetectionCount + 1,
                    fullDetectionTotalNs = stats.fullDetectionTotalNs + elapsedNs
                )
                if (elapsedNs > 0) fullTimings.add(elapsedNs)
            }
            "TRACKED_RESAMPLED" -> {
                stats = stats.copy(
                    trackedResampledFrames = stats.trackedResampledFrames + 1,
                    trackedResampledCount = stats.trackedResampledCount + 1,
                    trackedResampledTotalNs = stats.trackedResampledTotalNs + elapsedNs
                )
                if (elapsedNs > 0) trackedTimings.add(elapsedNs)
            }
            "TRACKED_HOMOGRAPHY" ->
                stats = stats.copy(heldFrames = stats.heldFrames + 1)
        }
    }

    private fun accumulateCrc(transportFrame: Any?, transportError: String?) {
        if (transportFrame != null) {
            stats = stats.copy(crcValidFrames = stats.crcValidFrames + 1)
        } else {
            when (transportError) {
                "Frame CRC16 mismatch" ->
                    stats = stats.copy(crcMismatchFrames = stats.crcMismatchFrames + 1)
                "Uncertain cells present" ->
                    stats = stats.copy(uncertainFrames = stats.uncertainFrames + 1)
                else ->
                    stats = stats.copy(otherTransportRejectedFrames = stats.otherTransportRejectedFrames + 1)
            }
        }
    }

    // ── accumulator-outcome snapshot ──────────────────────────────────
    // Captured by the caller around addFrame() so that the collector can
    // classify outcomes without modifying the accumulator.
    data class AccPreState(
        val uniqueFrames: Int,
        val duplicateCount: Int,
        val conflictCount: Int,
        val sessionId: Int,
        val totalFrames: Int
    )

    data class AccPostState(
        val uniqueFrames: Int,
        val duplicateCount: Int,
        val conflictCount: Int
    )

    /**
     * Called after accumulator.addFrame().
     *
     * @param pre    accumulator snapshot BEFORE addFrame
     * @param post   accumulator snapshot AFTER addFrame
     * @param frame  the transport frame that was offered (may be null if
     *               the caller skipped addFrame entirely; frame is then
     *               treated as a rejected frame)
     * @param pkg    return value of addFrame (null if not completing)
     */
    @Synchronized
    fun recordAccumulatorOutcome(
        pre: AccPreState,
        post: AccPostState,
        frame: V6TransportFrame?,
        pkg: V6TransferPackage?
    ) {
        if (state == State.COMPLETE) return

        // ── completion ─────────────────────────────────────────────────
        if (pkg != null) {
            val f = frame
            // Record completing frame in acquisition tracking.
            if (f != null) {
                val fid = f.frameId
                val fn = firstAcceptNs
                val vh = validHits
                if (fn != null && fid in fn.indices && fn[fid] == 0L) {
                    fn[fid] = System.nanoTime()
                    uniqueAcceptOrder.add(fid)
                }
                if (vh != null && fid in vh.indices) vh[fid]++
            }
            stats = stats.copy(
                uniqueFramesAccepted = stats.uniqueFramesAccepted + 1,
                totalLogicalFrames = f?.totalFrames ?: 0,
                completedFileBytes = pkg.fileData.size.toLong()
            )
            stats = stats.copy(activeTransferNs = System.nanoTime() - activeStartNs)
            state = State.COMPLETE
            return
        }

        if (frame == null) return

        // ── classify null-return via before/after deltas ───────────────
        val deltaUnique = post.uniqueFrames - pre.uniqueFrames
        val deltaDup = post.duplicateCount - pre.duplicateCount
        val deltaConflict = post.conflictCount - pre.conflictCount

        when {
            deltaConflict > 0 -> {
                stats = stats.copy(conflictFrames = stats.conflictFrames + 1)
            }
            deltaDup > 0 -> {
                stats = stats.copy(duplicateFrames = stats.duplicateFrames + 1)
                // Count valid hit for this frame ID.
                val hits = validHits
                val fid = frame.frameId
                if (hits != null && fid in hits.indices) hits[fid]++
            }
            deltaUnique > 0 -> {
                stats = stats.copy(uniqueFramesAccepted = stats.uniqueFramesAccepted + 1)

                val now = System.nanoTime()
                val fid = frame.frameId

                if (stats.totalLogicalFrames == 0 && pre.sessionId == -1) {
                    stats = stats.copy(totalLogicalFrames = frame.totalFrames)
                }

                // Transition from SCANNING to ACTIVE (must set activeStartNs
                // BEFORE recording the frame, so the timestamp offset is 0).
                if (state == State.SCANNING) {
                    activeStartNs = System.nanoTime()
                    stats = stats.copy(acquisitionNs = activeStartNs - collectorStartNs)
                    accumulateDetectorResult(
                        lastResultSource, lastResultBorderFound,
                        lastResultOrientationResolved,
                        lastResultDetectorStartNs, lastResultDetectorEndNs
                    )
                    accumulateCrc(
                        if (lastResultCrcValid) Any() else null,
                        lastResultCrcError
                    )
                    state = State.ACTIVE
                }

                // Allocate arrays the first time we know totalFrames.
                if (firstAcceptNs == null) {
                    val n = frame.totalFrames
                    firstAcceptNs = LongArray(n)
                    validHits = IntArray(n)
                }
                val fn = firstAcceptNs!!
                val vh = validHits!!

                // Record first-accept timestamp if not already seen (belt-and-suspenders).
                if (fn[fid] == 0L && fid in fn.indices) {
                    fn[fid] = now
                    uniqueAcceptOrder.add(fid)
                }
                if (fid in vh.indices) vh[fid]++
            }
            else -> {
                if (pre.sessionId != -1 && frame.sessionId != pre.sessionId) {
                    stats = stats.copy(foreignSessionRejected = stats.foreignSessionRejected + 1)
                } else if (pre.sessionId != -1 && frame.totalFrames != pre.totalFrames) {
                    stats = stats.copy(inconsistentTotalFramesRejected = stats.inconsistentTotalFramesRejected + 1)
                }
                // No else / otherTransportRejected — CRC outcomes are counted
                // separately via recordCrcOutcome on the main executor.
            }
        }
    }

    @Synchronized
    fun buildSnapshot(): V6TransferStats {
        // ── compute acquisition latency milestones ─────────────────
        val fn = firstAcceptNs
        val vh = validHits
        val order = uniqueAcceptOrder.toList()
        val startNs = activeStartNs

        var p25 = 0L; var p50 = 0L; var p75 = 0L; var p90 = 0L; var p100 = 0L
        var longestGap = 0L
        var last5Ids = ""; var last5Times = LongArray(0)
        var minHits = 0; var maxHits = 0

        if (fn != null && order.isNotEmpty() && startNs > 0) {
            // Compute milestones as time offsets from ACTIVE start.
            // For the SCANNING→ACTIVE frame, fn[id] ≈ activeStartNs so offset ≈ 0.
            val total = order.size
            val offsets = order.map { fn[it] - startNs }.toList()

            fun milestone(fraction: Double): Long {
                // ceil(total * fraction) → the required count, then index = requiredCount - 1.
                val required = kotlin.math.ceil(total * fraction).toInt().coerceIn(1, total)
                return offsets[required - 1].coerceAtLeast(0L)
            }
            p25 = milestone(0.25)
            p50 = milestone(0.50)
            p75 = milestone(0.75)
            p90 = milestone(0.90)
            p100 = milestone(1.0)

            // Longest gap between consecutive unique acceptances.
            for (i in 1 until order.size) {
                val gap = offsets[i] - offsets[i - 1]
                if (gap > longestGap) longestGap = gap
            }

            // Last 5 unique frame IDs and their acceptance times.
            val lastN = order.takeLast(5)
            last5Ids = lastN.joinToString(",")
            last5Times = LongArray(lastN.size) { offsets[order.indexOf(lastN[it])].coerceAtLeast(0L) }

            // Min/max valid hits per frame ID.
            if (vh != null) {
                minHits = vh.filter { it > 0 }.minOrNull() ?: 0
                maxHits = vh.maxOrNull() ?: 0
            }
        }

        return stats.copy(
            // Camera config overlay
            analysisWidth = camConfig.width,
            analysisHeight = camConfig.height,
            cameraSupportedRanges = camConfig.supportedRanges,
            cameraSelectedRange = camConfig.selectedRange,
            cameraBoundRange = camConfig.boundRange,
            cameraFpsFallback = camConfig.fallback,
            // Acquisition latency
            acquisition25PctNs = p25,
            acquisition50PctNs = p50,
            acquisition75PctNs = p75,
            acquisition90PctNs = p90,
            acquisition100PctNs = p100,
            acquisitionLongestGapNs = longestGap,
            acquisitionLast5Ids = last5Ids,
            acquisitionLast5TimesNs = last5Times,
            acquisitionMinHits = minHits,
            acquisitionMaxHits = maxHits,
            // Timing lists
            fullDetectionTimesNs = fullTimings.toList(),
            trackedResampledTimesNs = trackedTimings.toList(),
            sensorDeltasNs = sensorDeltas.toList(),
            callbackDeltasNs = callbackDeltas.toList(),
            occupancyDurationsNs = occupancyDurations.toList(),
            preDetectorTimesNs = preDetectorTimings.toList(),
            postDetectorTimesNs = postDetectorTimings.toList()
        )
    }
}
