package com.superqr.android.vision.v6.benchmark

import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.replay.*
import org.junit.Test
import java.io.File

class V6OfflineReplayBenchmarkTest {

    @Test
    fun runOfflineReplayBenchmark() {
        val datasetEnv = System.getenv("SUPERQR_V6_DATASET") ?: System.getProperty("SUPERQR_V6_DATASET")
        if (datasetEnv.isNullOrBlank()) {
            println("[V6OfflineReplayBenchmarkTest] Environment variable SUPERQR_V6_DATASET is not set. Skipping offline benchmark.")
            return
        }

        val datasetDir = File(datasetEnv)
        if (!datasetDir.exists() || !datasetDir.isDirectory) {
            println("[V6OfflineReplayBenchmarkTest] Dataset directory does not exist or is not a directory: ${datasetDir.absolutePath}. Skipping.")
            return
        }

        println("[V6OfflineReplayBenchmarkTest] Running V6 offline benchmark on dataset: ${datasetDir.absolutePath}")

        // Load the visual contract (required by V6StaticDetector).
        val contractFile = File("src/main/assets/visual_contract.json")
        V6Contract.loadAndVerifyBytes(contractFile.readBytes())

        val manifestFile = File(datasetDir, "manifest.json")
        val results = mutableListOf<V6ReplayResult>()

        if (manifestFile.exists() && manifestFile.isFile) {
            val manifest = V6BenchmarkManifest.loadFromFile(manifestFile)
            println("[V6OfflineReplayBenchmarkTest] Loaded ${manifest.cases.size} test cases from manifest.json")

            for (testCase in manifest.cases) {
                val zipFile = File(datasetDir, testCase.zip)
                if (!zipFile.exists()) {
                    println("[V6OfflineReplayBenchmarkTest] WARNING: ZIP file not found: ${zipFile.absolutePath}")
                    results.add(
                        V6ReplayResult(
                            label = testCase.label,
                            zipFileName = testCase.zip,
                            borderFound = false,
                            orientationResolved = false,
                            classificationSource = "NONE",
                            correctSymbols = null,
                            symbolErrors = null,
                            symbolErrorRate = null,
                            uncertainCount = 0,
                            uncertainRate = 0.0,
                            parseAttempted = false,
                            transportValid = false,
                            sessionId = null,
                            frameId = null,
                            totalFrames = null,
                            expectedSessionId = testCase.expectedSessionId,
                            expectedFrameId = testCase.expectedFrameId,
                            expectedTotalFrames = testCase.expectedTotalFrames,
                            packetSuccess = false,
                            processingTimeMs = 0L,
                            failureReason = "ZIP file not found: ${zipFile.name}"
                        )
                    )
                    continue
                }

                try {
                    val frame = V6DiagnosticZipLoader.loadFromZipFile(zipFile)
                    val result = V6ReplayEngine.replayFrame(frame, label = testCase.label, zipFileName = zipFile.name, testCase = testCase)
                    results.add(result)
                } catch (e: Throwable) {
                    println("[V6OfflineReplayBenchmarkTest] ERROR processing ${zipFile.name}: ${e.message}")
                    results.add(
                        V6ReplayResult(
                            label = testCase.label,
                            zipFileName = zipFile.name,
                            borderFound = false,
                            orientationResolved = false,
                            classificationSource = "NONE",
                            correctSymbols = null,
                            symbolErrors = null,
                            symbolErrorRate = null,
                            uncertainCount = 0,
                            uncertainRate = 0.0,
                            parseAttempted = false,
                            transportValid = false,
                            sessionId = null,
                            frameId = null,
                            totalFrames = null,
                            expectedSessionId = testCase.expectedSessionId,
                            expectedFrameId = testCase.expectedFrameId,
                            expectedTotalFrames = testCase.expectedTotalFrames,
                            packetSuccess = false,
                            processingTimeMs = 0L,
                            failureReason = "Load/replay error: ${e.message}"
                        )
                    )
                }
            }
        } else {
            val zipFiles = datasetDir.listFiles { _, name -> name.endsWith(".zip", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()
            println("[V6OfflineReplayBenchmarkTest] Found ${zipFiles.size} ZIP files in directory")

            for (zipFile in zipFiles) {
                try {
                    val frame = V6DiagnosticZipLoader.loadFromZipFile(zipFile)
                    val result = V6ReplayEngine.replayFrame(frame, label = zipFile.nameWithoutExtension, zipFileName = zipFile.name)
                    results.add(result)
                } catch (e: Throwable) {
                    println("[V6OfflineReplayBenchmarkTest] ERROR processing ${zipFile.name}: ${e.message}")
                    results.add(
                        V6ReplayResult(
                            label = zipFile.nameWithoutExtension,
                            zipFileName = zipFile.name,
                            borderFound = false,
                            orientationResolved = false,
                            classificationSource = "NONE",
                            correctSymbols = null,
                            symbolErrors = null,
                            symbolErrorRate = null,
                            uncertainCount = 0,
                            uncertainRate = 0.0,
                            parseAttempted = false,
                            transportValid = false,
                            sessionId = null,
                            frameId = null,
                            totalFrames = null,
                            expectedSessionId = null,
                            expectedFrameId = null,
                            expectedTotalFrames = null,
                            packetSuccess = false,
                            processingTimeMs = 0L,
                            failureReason = "Load/replay error: ${e.message}"
                        )
                    )
                }
            }
        }

        val summary = V6ReplayAggregator.aggregate(results)
        val outputDir = File("build/reports/v6-replay")
        V6ReplayReportExporter.exportReports(results, summary, outputDir)

        val summaryTxt = V6ReplayReportExporter.generateSummaryTxt(summary)
        println(summaryTxt)
        println("[V6OfflineReplayBenchmarkTest] Reports exported to: ${outputDir.absolutePath}")
    }
}
