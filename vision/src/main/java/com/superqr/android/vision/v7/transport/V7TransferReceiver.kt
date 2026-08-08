package com.superqr.android.vision.v7.transport

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v7_capacity_lab.V7Calibrator
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier

/**
 * Production V7 optical payload decoder.
 *
 * Geometry acquisition/tracking is intentionally supplied by the physically
 * validated V6 detector. Only the 40x40 V7 payload is sampled and decoded here.
 */
class V7TransferReceiver {
    val sampler = V7HighDensitySampler()
    val calibrator = V7Calibrator(4)
    val classifier = V7SoftClassifier()

    var probeMode: V7HighDensitySampler.ProbeMode = V7HighDensitySampler.ProbeMode.CROSS_5

    data class Timing(
        val samplingUs: Long = 0,
        val classificationUs: Long = 0,
        val transportUs: Long = 0,
        val totalUs: Long = 0,
    )

    data class DecodeResult(
        val trackingState: String,
        val calibratedCount: Int,
        val validSamples: Int,
        val erasureCount: Int,
        val acceptedFrame: V7TransportFrame?,
        val transportError: String?,
        val timing: Timing,
    )

    init {
        sampler.setGridSize(V7Transport.GRID_SIZE, doubleArrayOf(200.0, 200.0, 800.0, 800.0))
        classifier.setCellCount(V7Transport.CELL_COUNT)
    }

    fun reset() {
        calibrator.reset()
    }

    fun analyze(
        geometry: V6StaticResult,
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        chromaReader: ChromaPixelReader?,
    ): DecodeResult {
        val t0 = System.nanoTime()
        updatePilotCalibration(geometry)

        val hInv = geometry.finalInvHomography
        if (!geometry.borderFound || !geometry.orientationResolved || hInv == null) {
            return DecodeResult(
                trackingState = geometry.trackingState,
                calibratedCount = calibrator.calibratedCount(),
                validSamples = 0,
                erasureCount = V7Transport.CELL_COUNT,
                acceptedFrame = null,
                transportError = null,
                timing = Timing(totalUs = (System.nanoTime() - t0) / 1000),
            )
        }

        val ts0 = System.nanoTime()
        val validSamples = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 ->
                sampler.sampleCenter1(hInv, lumaBytes, width, height, chromaReader)
            V7HighDensitySampler.ProbeMode.CROSS_5 ->
                sampler.sampleCross5(hInv, lumaBytes, width, height, chromaReader)
        }
        val samplingUs = (System.nanoTime() - ts0) / 1000

        if (!calibrator.isFullyCalibrated()) {
            return DecodeResult(
                geometry.trackingState,
                calibrator.calibratedCount(),
                validSamples,
                V7Transport.CELL_COUNT,
                null,
                null,
                Timing(samplingUs = samplingUs, totalUs = (System.nanoTime() - t0) / 1000),
            )
        }

        val tc0 = System.nanoTime()
        classifier.setCenters(calibrator.getCentersSnapshot())
        val y = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getYCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getYCross5()
        }
        val u = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getUCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getUCross5()
        }
        val v = when (probeMode) {
            V7HighDensitySampler.ProbeMode.CENTER_1 -> sampler.getVCenters()
            V7HighDensitySampler.ProbeMode.CROSS_5 -> sampler.getVCross5()
        }
        classifier.classify(y, u, v, V7Transport.CELL_COUNT)
        val classificationUs = (System.nanoTime() - tc0) / 1000

        var erasures = 0
        for (symbol in classifier.bestSymbols) {
            if (symbol == V7SoftClassifier.ERASURE_MARKER) erasures++
        }

        var accepted: V7TransportFrame? = null
        var error: String? = null
        val tt0 = System.nanoTime()
        if (erasures == 0 && validSamples == V7Transport.CELL_COUNT) {
            val bytes = V7Transport.symbolsToBytes(classifier.bestSymbols)
            if (bytes != null) {
                try {
                    accepted = V7Transport.parseFrame(bytes)
                } catch (e: V7TransportError) {
                    error = e.message
                }
            }
        }
        val transportUs = (System.nanoTime() - tt0) / 1000

        return DecodeResult(
            trackingState = geometry.trackingState,
            calibratedCount = calibrator.calibratedCount(),
            validSamples = validSamples,
            erasureCount = erasures,
            acceptedFrame = accepted,
            transportError = error,
            timing = Timing(
                samplingUs = samplingUs,
                classificationUs = classificationUs,
                transportUs = transportUs,
                totalUs = (System.nanoTime() - t0) / 1000,
            ),
        )
    }

    private fun updatePilotCalibration(result: V6StaticResult) {
        if (result.pilotYUVs.isEmpty()) return
        val pilots = arrayOf("BLACK", "WHITE", "RED", "BLUE")
        for (idx in pilots.indices) {
            val yuv = result.pilotYUVs[pilots[idx]] ?: continue
            if (!calibrator.isCalibrated(idx)) {
                calibrator.setPilotCenter(idx, yuv[0], yuv[1], yuv[2])
            } else {
                calibrator.updateCenterEMA(idx, yuv[0], yuv[1], yuv[2], alpha = 0.05)
            }
        }
    }
}
