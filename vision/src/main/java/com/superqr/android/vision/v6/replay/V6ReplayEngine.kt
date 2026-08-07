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
        // Instantiate fresh detector so temporal tracker state does NOT influence offline single-frame replay
        val detector = V6StaticDetector()

        val packedLuma = V6ReplayLumaPacker.pack(frame)
        val chromaSampler = V6ReplayChromaSampler(frame)

        val patternMode = testCase?.patternName ?: frame.patternName ?: "deterministic_random"

        val staticResult: V6StaticResult = detector.detect(
            luma = packedLuma.bytes,
            width = packedLuma.width,
            height = packedLuma.height,
            mode = patternMode,
            chromaReader = chromaSampler
        )
        detector.close()

        val payload = staticResult.diagnosticPayload

        val expSessionId = testCase?.expectedSessionId ?: frame.transportSessionId
        val expFrameId = testCase?.expectedFrameId ?: frame.transportFrameId
        val expTotalFrames = testCase?.expectedTotalFrames ?: frame.transportTotalFrames
        val expFrameHex = testCase?.expectedFrameHex ?: frame.transportPayloadHex

        // Determine ground truth expected 400 palette indexes if available
        val expectedIndexes: IntArray? = when {
            expFrameHex != null && expFrameHex.length >= 200 -> {
                val hexBytes = V6ExpectedGridGenerator.parseHexToBytes(expFrameHex)
                if (hexBytes.size == 100) V6ExpectedGridGenerator.bytesToPaletteIndexes(hexBytes) else null
            }
            testCase?.patternName != null -> V6ExpectedGridGenerator.generateForStaticPattern(testCase.patternName)
            frame.patternName != null -> V6ExpectedGridGenerator.generateForStaticPattern(frame.patternName)
            else -> null
        }

        var correctSymbols: Int? = null
        var symbolErrors: Int? = null
        var symbolErrorRate: Double? = null

        if (expectedIndexes != null && staticResult.borderFound && staticResult.orientationResolved && payload != null && payload.cellDetails.size == 400) {
            var correct = 0
            for (r in 0 until 20) {
                for (c in 0 until 20) {
                    val idx = r * 20 + c
                    val cellDetail = payload.cellDetails[idx]
                    if (cellDetail.decodedIdx == expectedIndexes[idx]) {
                        correct++
                    }
                }
            }
            correctSymbols = correct
            symbolErrors = 400 - correct
            symbolErrorRate = symbolErrors.toDouble() / 400.0
        } else if (staticResult.borderFound && staticResult.orientationResolved && payload != null && payload.cellDetails.size == 400) {
            correctSymbols = staticResult.colorCorrect
            symbolErrors = 400 - staticResult.colorCorrect - staticResult.colorUncertain
            symbolErrorRate = symbolErrors.toDouble() / 400.0
        }

        val uncertainCount = staticResult.colorUncertain
        val uncertainRate = uncertainCount.toDouble() / 400.0

        val parseAttempted = (staticResult.diagnosticPayload?.classificationSource == "FULL_DETECTION" || staticResult.borderFound)
        val transportValid = (staticResult.transportFrame != null)

        val actualSessionId = staticResult.transportSessionId
        val actualFrameId = staticResult.transportFrameId
        val actualTotalFrames = staticResult.transportTotalFrames

        var packetSuccess = transportValid
        if (packetSuccess && expSessionId != null && actualSessionId != expSessionId) {
            packetSuccess = false
        }
        if (packetSuccess && expFrameId != null && actualFrameId != expFrameId) {
            packetSuccess = false
        }
        if (packetSuccess && expTotalFrames != null && actualTotalFrames != expTotalFrames) {
            packetSuccess = false
        }

        return V6ReplayResult(
            label = label,
            zipFileName = zipFileName,
            borderFound = staticResult.borderFound,
            orientationResolved = staticResult.orientationResolved,
            classificationSource = staticResult.diagnosticPayload?.classificationSource ?: if (staticResult.borderFound) "FULL_DETECTION" else "NONE",
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
            failureReason = staticResult.failureReason
        )
    }
}
