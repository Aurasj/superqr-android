package com.superqr.android.vision.v6.replay

import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.v6.model.V6StaticResult

object V6ReplayEngine {

    fun replayFrame(
        frame: V6RawYuvFrame,
        label: String = "frame",
        zipFileName: String = "frame.zip",
        testCase: V6BenchmarkTestCase? = null
    ): V6ReplayResult {
        // A fresh detector makes one replay case independent of temporal tracker history.
        val detector = V6StaticDetector()
        val packedLuma = V6ReplayLumaPacker.pack(frame)
        val chromaSampler = V6ReplayChromaSampler(frame)
        val patternMode = testCase?.patternName ?: frame.patternName ?: "deterministic_random"

        val staticResult: V6StaticResult = try {
            detector.detect(
                luma = packedLuma.bytes,
                width = packedLuma.width,
                height = packedLuma.height,
                mode = patternMode,
                chromaReader = chromaSampler
            )
        } finally {
            detector.close()
        }

        val payload = staticResult.diagnosticPayload

        // IMPORTANT: fields stored inside a diagnostic ZIP came from the detector that
        // created that ZIP. They are useful provenance, but are NOT independent ground
        // truth and must never be used to score the replay of that same frame.
        val expSessionId = testCase?.expectedSessionId
        val expFrameId = testCase?.expectedFrameId
        val expTotalFrames = testCase?.expectedTotalFrames
        val expFrameHex = testCase?.expectedFrameHex
        val packetGroundTruthAvailable = expFrameHex != null ||
            expSessionId != null || expFrameId != null || expTotalFrames != null

        val expectedIndexes: IntArray? = when {
            expFrameHex != null -> {
                val hexBytes = V6ExpectedGridGenerator.parseHexToBytes(expFrameHex)
                if (hexBytes.size == 100) V6ExpectedGridGenerator.bytesToPaletteIndexes(hexBytes) else null
            }
            testCase?.patternName != null ->
                V6ExpectedGridGenerator.generateForStaticPattern(testCase.patternName)
            else -> null
        }

        var correctSymbols: Int? = null
        var symbolErrors: Int? = null
        var symbolErrorRate: Double? = null

        if (expectedIndexes != null && staticResult.borderFound && staticResult.orientationResolved &&
            payload != null && payload.cellDetails.size == 400
        ) {
            var correct = 0
            for (i in 0 until 400) {
                if (payload.cellDetails[i].decodedIdx == expectedIndexes[i]) correct++
            }
            correctSymbols = correct
            symbolErrors = 400 - correct
            symbolErrorRate = symbolErrors.toDouble() / 400.0
        }

        val uncertainCount = staticResult.colorUncertain
        val uncertainRate = uncertainCount.toDouble() / 400.0
        val source = payload?.classificationSource ?: if (staticResult.borderFound) "FULL_DETECTION" else "NONE"

        // Parsing is attempted only after a fresh full classification reaches the
        // transport parser. Border detection by itself is not a parse attempt.
        val parseAttempted = source == "FULL_DETECTION" &&
            staticResult.borderFound && staticResult.orientationResolved &&
            staticResult.transportError != "Uncertain cells present"
        val transportValid = staticResult.transportFrame != null

        val actualSessionId = staticResult.transportSessionId
        val actualFrameId = staticResult.transportFrameId
        val actualTotalFrames = staticResult.transportTotalFrames

        // PSR is a benchmark score only when independent manifest ground truth exists.
        // A CRC-valid replay without ground truth is still reported as transportValid,
        // but is deliberately NOT promoted to packetSuccess.
        var packetSuccess = packetGroundTruthAvailable && transportValid
        if (packetSuccess && expSessionId != null && actualSessionId != expSessionId) packetSuccess = false
        if (packetSuccess && expFrameId != null && actualFrameId != expFrameId) packetSuccess = false
        if (packetSuccess && expTotalFrames != null && actualTotalFrames != expTotalFrames) packetSuccess = false
        if (packetSuccess && expFrameHex != null) {
            val expected = V6ExpectedGridGenerator.parseHexToBytes(expFrameHex)
            val actual = try {
                val indexes = payload?.cellDetails?.map { it.decodedIdx }?.toIntArray()
                if (indexes != null && indexes.size == 400 && indexes.all { it in 0..3 }) {
                    com.superqr.android.vision.v6.transport.V6Transport.paletteIndexesToBytes(indexes)
                } else null
            } catch (_: Throwable) {
                null
            }
            if (actual == null || !actual.contentEquals(expected)) packetSuccess = false
        }

        return V6ReplayResult(
            label = label,
            zipFileName = zipFileName,
            borderFound = staticResult.borderFound,
            orientationResolved = staticResult.orientationResolved,
            classificationSource = source,
            correctSymbols = correctSymbols,
            symbolErrors = symbolErrors,
            symbolErrorRate = symbolErrorRate,
            uncertainCount = uncertainCount,
            uncertainRate = uncertainRate,
            parseAttempted = parseAttempted,
            transportValid = transportValid,
            sessionId = actualSessionId,
            frameId = actualFrameId,
            totalFrames = actualTotalFrames,
            expectedSessionId = expSessionId,
            expectedFrameId = expFrameId,
            expectedTotalFrames = expTotalFrames,
            packetSuccess = packetSuccess,
            processingTimeMs = staticResult.processingTimeMs,
            failureReason = staticResult.failureReason ?: staticResult.transportError,
            packetGroundTruthAvailable = packetGroundTruthAvailable
        )
    }
}
