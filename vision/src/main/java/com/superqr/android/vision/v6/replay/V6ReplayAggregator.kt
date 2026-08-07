package com.superqr.android.vision.v6.replay

data class V6ReplaySummary(
    val totalCases: Int,
    val borderFoundCount: Int,
    val borderFoundRate: Double,
    val orientationResolvedCount: Int,
    val orientationResolvedRate: Double,
    val parseAttemptedCount: Int,
    val transportValidCount: Int,
    val packetSuccessCount: Int,
    val packetSuccessRate: Double,
    val averageSER: Double?,
    val meanProcessingMs: Double,
    val medianProcessingMs: Double,
    val p95ProcessingMs: Double,
    val totalCrcValidFrames: Int,
    val totalCrcInvalidFrames: Int,
    /** Number of cases with independent manifest packet ground truth. */
    val packetGroundTruthCount: Int = 0
)

object V6ReplayAggregator {

    fun aggregate(results: List<V6ReplayResult>): V6ReplaySummary {
        val total = results.size
        if (total == 0) {
            return V6ReplaySummary(
                totalCases = 0,
                borderFoundCount = 0,
                borderFoundRate = 0.0,
                orientationResolvedCount = 0,
                orientationResolvedRate = 0.0,
                parseAttemptedCount = 0,
                transportValidCount = 0,
                packetSuccessCount = 0,
                packetSuccessRate = 0.0,
                averageSER = null,
                meanProcessingMs = 0.0,
                medianProcessingMs = 0.0,
                p95ProcessingMs = 0.0,
                totalCrcValidFrames = 0,
                totalCrcInvalidFrames = 0,
                packetGroundTruthCount = 0
            )
        }

        val borderFoundCount = results.count { it.borderFound }
        val orientationResolvedCount = results.count { it.orientationResolved }
        val parseAttemptedCount = results.count { it.parseAttempted }
        val transportValidCount = results.count { it.transportValid }
        val grounded = results.filter { it.packetGroundTruthAvailable }
        val packetGroundTruthCount = grounded.size
        val packetSuccessCount = grounded.count { it.packetSuccess }

        val borderFoundRate = borderFoundCount.toDouble() / total
        val orientationResolvedRate = orientationResolvedCount.toDouble() / total
        val packetSuccessRate = if (packetGroundTruthCount > 0) {
            packetSuccessCount.toDouble() / packetGroundTruthCount
        } else 0.0

        // SER is averaged only over cases that have independent expected symbols.
        val serList = results.mapNotNull { it.symbolErrorRate }
        val averageSER = if (serList.isNotEmpty()) serList.average() else null

        val times = results.map { it.processingTimeMs }.sorted()
        val meanProcessingMs = times.average()
        val medianProcessingMs = if (times.size % 2 == 1) {
            times[times.size / 2].toDouble()
        } else {
            (times[times.size / 2 - 1] + times[times.size / 2]).toDouble() / 2.0
        }
        val p95Index = kotlin.math.ceil(0.95 * times.size).toInt() - 1
        val p95ProcessingMs = times[p95Index.coerceIn(0, times.size - 1)].toDouble()

        val totalCrcValidFrames = transportValidCount
        val totalCrcInvalidFrames = (parseAttemptedCount - transportValidCount).coerceAtLeast(0)

        return V6ReplaySummary(
            totalCases = total,
            borderFoundCount = borderFoundCount,
            borderFoundRate = borderFoundRate,
            orientationResolvedCount = orientationResolvedCount,
            orientationResolvedRate = orientationResolvedRate,
            parseAttemptedCount = parseAttemptedCount,
            transportValidCount = transportValidCount,
            packetSuccessCount = packetSuccessCount,
            packetSuccessRate = packetSuccessRate,
            averageSER = averageSER,
            meanProcessingMs = meanProcessingMs,
            medianProcessingMs = medianProcessingMs,
            p95ProcessingMs = p95ProcessingMs,
            totalCrcValidFrames = totalCrcValidFrames,
            totalCrcInvalidFrames = totalCrcInvalidFrames,
            packetGroundTruthCount = packetGroundTruthCount
        )
    }
}
