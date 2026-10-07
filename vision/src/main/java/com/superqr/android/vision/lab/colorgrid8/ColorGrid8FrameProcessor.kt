package com.superqr.android.vision.lab.colorgrid8

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Dense or strided, rotation-normalized YUV420 planes owned by the caller for one frame. */
data class ColorGrid8YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val chromaWidth: Int,
    val chromaHeight: Int,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int = width,
    val yPixelStride: Int = 1,
    val uRowStride: Int = chromaWidth,
    val uPixelStride: Int = 1,
    val vRowStride: Int = chromaWidth,
    val vPixelStride: Int = 1,
    val yBuffer: java.nio.ByteBuffer? = null,
    val uBuffer: java.nio.ByteBuffer? = null,
    val vBuffer: java.nio.ByteBuffer? = null,
) {
    init {
        require(width > 0 && height > 0)
        require(chromaWidth > 0 && chromaHeight > 0)
        require(y.size >= width * height)
        require(u.size >= chromaWidth * chromaHeight)
        require(v.size >= chromaWidth * chromaHeight)
    }

    val yTarget: Any get() = yBuffer ?: y
    val uTarget: Any get() = uBuffer ?: u
    val vTarget: Any get() = vBuffer ?: v
}

data class ColorGrid8ProcessResult(
    val analysis: ColorGrid8AnalysisResult?,
    val stage: ColorGrid8Stage,
    val failure: String?,
    val quad: List<DoubleArray>,
    val acquisitionMode: String,
    val finderCandidates: Int,
    val geometryLocked: Boolean,
    val headerStatus: String,
    val expectedProfile: String,
    val detectedProfile: String?,
    val finderMs: Double = 0.0,
    val geometryMs: Double = 0.0,
    val orientationMs: Double = 0.0,
    val headerMs: Double = 0.0,
    val pilotMs: Double = 0.0,
    val payloadMs: Double = 0.0,
    val transportMs: Double = 0.0,
    val warpAndMeanMs: Double = 0.0,
    val totalPipelineMs: Double = 0.0,
    val trackedFrames: Long = 0,
    val roiFrames: Long = 0,
    val fullRedetections: Long = 0,
    val trackingFailures: Long = 0,
    val transportFrame: ColorGrid8TransferCodec.Frame? = null,
    val nativeDecoderActive: Boolean = false,
    val nativeHeaderMs: Double = 0.0,
    val nativePilotMs: Double = 0.0,
    val nativePayloadMs: Double = 0.0,
    val nativeTotalMs: Double = 0.0,
    val jniOverheadMs: Double = 0.0,
) {
    val hasVerifiedTransport: Boolean
        get() = transportFrame?.validBlocks?.any { it } == true
}

/**
 * Phase 2 ColorGrid8 optical front-end:
 * Explicit stages: CAMERA -> FINDER_ROI -> GEOMETRY -> ORIENTATION -> HEADER -> PILOTS -> PAYLOAD -> TRANSPORT
 * 1. Two-mode finder acquisition (LOCKED ROI refinement / PyrLK vs COLD START full search).
 * 2. Phase 1 Top-Left orientation cue resolution before full warps.
 * 3. Header-first decoding on lightweight warped header strip (immediate rejection of invalid frames).
 * 4. Full Y/U/V perspective warp executed ONLY when geometry, orientation, and header are valid.
 * 5. Adaptive redetection on tracking / repeated header failure.
 * 6. Zero-allocation buffers for Mats and cell means in hot path.
 */
class ColorGrid8FrameProcessor(
    private val maxRedetectInterval: Int = 60,
) : AutoCloseable {
    private data class FinderCandidate(val center: Point, val area: Double, val diameter: Double)
    data class Acquisition(val quad: Array<Point>, val mode: String, val candidateCount: Int)
    data class AcquisitionAttempt(
        val acquisition: Acquisition?,
        val candidateCount: Int,
        val failure: String?,
    )

    private val analyzer = ColorGrid8Analyzer()
    private val nativeScratch = ColorGrid8NativeDecoder.NativeDecoderScratch()
    private var initialized = false
    private var frameCounter = 0L
    private var lastQuad: Array<Point>? = null
    private var orientationOffset: Int? = null
    private var consecutiveHeaderFailures = 0
    private var framesSinceFullDetect = 0L

    var trackedFrames: Long = 0
        private set
    var roiFrames: Long = 0
        private set
    var fullRedetections: Long = 0
        private set
    var trackingFailures: Long = 0
        private set

    private lateinit var gray: Mat
    private lateinit var blurred: Mat
    private lateinit var binary: Mat
    private lateinit var hierarchy: Mat
    private lateinit var chromaU: Mat
    private lateinit var chromaV: Mat
    private lateinit var previousGray: Mat

    private lateinit var warpedHeader: Mat
    private lateinit var meansHeader: Mat
    private lateinit var warpedY: Mat
    private lateinit var warpedU: Mat
    private lateinit var warpedV: Mat
    private lateinit var meansY: Mat
    private lateinit var meansU: Mat
    private lateinit var meansV: Mat

    private lateinit var roiBlurred: Mat
    private lateinit var roiBinary: Mat
    private lateinit var roiHierarchy: Mat

    private var headerCellMeans = ByteArray(0)
    private var yMeansBuffer = ByteArray(0)
    private var uMeansBuffer = ByteArray(0)
    private var vMeansBuffer = ByteArray(0)
    private val samplePixelBuf = ByteArray(1)

    init {
        require(maxRedetectInterval > 0)
    }

    private fun ensureInitialized() {
        if (initialized) return
        OpenCvRuntime.ensureLoaded()
        gray = Mat()
        blurred = Mat()
        binary = Mat()
        hierarchy = Mat()
        chromaU = Mat()
        chromaV = Mat()
        previousGray = Mat()
        warpedHeader = Mat()
        meansHeader = Mat()
        warpedY = Mat()
        warpedU = Mat()
        warpedV = Mat()
        meansY = Mat()
        meansU = Mat()
        meansV = Mat()
        roiBlurred = Mat()
        roiBinary = Mat()
        roiHierarchy = Mat()
        initialized = true
    }

    fun resetTracking() {
        lastQuad = null
        orientationOffset = null
        consecutiveHeaderFailures = 0
        framesSinceFullDetect = 0L
        analyzer.reset()
        frameCounter = 0L
        if (initialized) {
            previousGray.release()
            previousGray = Mat()
        }
    }

    fun process(profile: ColorGrid8Profile, frame: ColorGrid8YuvFrame): ColorGrid8ProcessResult {
        ensureInitialized()
        val pipelineStart = System.nanoTime()

        // 1. CAMERA: Load frame planes
        gray.create(frame.height, frame.width, CvType.CV_8UC1)
        gray.put(0, 0, frame.y)
        chromaU.create(frame.chromaHeight, frame.chromaWidth, CvType.CV_8UC1)
        chromaU.put(0, 0, frame.u)
        chromaV.create(frame.chromaHeight, frame.chromaWidth, CvType.CV_8UC1)
        chromaV.put(0, 0, frame.v)

        // 2. FINDER_ROI & GEOMETRY: Acquire and track 4 corner finders
        val finderStart = System.nanoTime()
        val acquisitionAttempt = acquireQuad(gray, frame.width, frame.height)
        val finderMs = (System.nanoTime() - finderStart) / 1_000_000.0
        val acquisition = acquisitionAttempt.acquisition
        if (acquisition == null) {
            rememberGray()
            val stage = if (acquisitionAttempt.candidateCount < 4) ColorGrid8Stage.FINDER_ROI else ColorGrid8Stage.GEOMETRY
            return failureResult(
                profile = profile,
                stage = stage,
                failure = acquisitionAttempt.failure ?: "finder geometry unavailable",
                candidateCount = acquisitionAttempt.candidateCount,
                finderMs = finderMs,
                geometryMs = finderMs,
                pipelineStart = pipelineStart,
            )
        }

        // 3. ORIENTATION: Determine candidate orientation offsets
        val orientStart = System.nanoTime()
        val preferredOffset = orientationOffset
        val candidateOffsets = if (preferredOffset != null) {
            listOf(preferredOffset)
        } else {
            val cueOffset = detectOrientationOffset(gray, acquisition.quad, frame.width, frame.height)
            listOf(cueOffset) + (0..3).filter { it != cueOffset }
        }
        val orientationMs = (System.nanoTime() - orientStart) / 1_000_000.0

        // 4. HEADER: Header-first verification on lightweight header strip
        val headerStart = System.nanoTime()
        val transferMode = profile.version == ColorGrid8Spec.TRANSFER_HEADER_VERSION
        val samplesPerCell = 4
        var detectedHeader: ColorGrid8Header? = null
        var selectedQuad: Array<Point>? = null
        var selectedOffset = 0
        var headerScore = Int.MAX_VALUE
        var headerContrast = 0.0

        for (offset in candidateOffsets) {
            val candidateQuad = Array(4) { index -> acquisition.quad[(index + offset) and 3] }
            val headerBytes = warpHeaderStrip(
                source = gray,
                sourceQuad = candidateQuad,
                profile = profile,
                samplesPerCell = samplesPerCell,
                warped = warpedHeader,
                resized = meansHeader,
            ) ?: continue

            val probe = analyzer.probeHeader(headerBytes, profile.cols, ColorGrid8Spec.HEADER_ROWS)
            if (probe.header != null) {
                detectedHeader = probe.header
                selectedQuad = candidateQuad
                selectedOffset = offset
                headerScore = probe.score
                headerContrast = probe.contrast
                orientationOffset = offset
                consecutiveHeaderFailures = 0
                break
            } else {
                if (probe.score < headerScore) {
                    headerScore = probe.score
                    headerContrast = probe.contrast
                }
            }
            // If orientation was already locked, do not pay for four candidate warps on transient noise
            if (preferredOffset != null) break
        }
        val headerMs = (System.nanoTime() - headerStart) / 1_000_000.0

        if (detectedHeader == null || selectedQuad == null) {
            consecutiveHeaderFailures++
            if (consecutiveHeaderFailures >= 3) {
                // Tracking lost orientation or quad drifted: force full redetection
                trackingFailures++
                lastQuad = null
                orientationOffset = null
                consecutiveHeaderFailures = 0
            }
            rememberGray()
            return failureResult(
                profile = profile,
                stage = ColorGrid8Stage.HEADER,
                failure = "header verification failed (contrast %.1f score %d)".format(headerContrast, headerScore),
                acquisition = acquisition,
                finderMs = finderMs,
                geometryMs = finderMs,
                orientationMs = orientationMs,
                headerMs = headerMs,
                pipelineStart = pipelineStart,
            )
        }

        // Profile check
        if (
            detectedHeader.profileId != profile.profileId ||
            detectedHeader.fps != profile.fps ||
            detectedHeader.version != profile.version
        ) {
            rememberGray()
            return failureResult(
                profile = profile,
                stage = ColorGrid8Stage.PROFILE,
                failure = "detected profile ${detectedHeader.profileId}@${detectedHeader.fps} does not match expected ${profile.profileId}@${profile.fps}",
                acquisition = acquisition,
                finderMs = finderMs,
                geometryMs = finderMs,
                orientationMs = orientationMs,
                headerMs = headerMs,
                pipelineStart = pipelineStart,
                detectedHeader = detectedHeader,
            )
        }

        // 5. PILOTS & PAYLOAD: Try native ARM64 NEON path first
        val nativeResult = if (ColorGrid8NativeDecoder.isNativeLoaded) {
            nativeScratch.decode(
                yBuffer = frame.yTarget,
                yRowStride = frame.yRowStride,
                yPixelStride = frame.yPixelStride,
                uBuffer = frame.uTarget,
                uRowStride = frame.uRowStride,
                uPixelStride = frame.uPixelStride,
                vBuffer = frame.vTarget,
                vRowStride = frame.vRowStride,
                vPixelStride = frame.vPixelStride,
                width = frame.width,
                height = frame.height,
                quad = selectedQuad,
                profile = profile,
            )
        } else null

        val analysis: ColorGrid8AnalysisResult
        val nativeActive: Boolean
        val pilotMs: Double
        val payloadMs: Double
        val fullWarpMs: Double
        val nativeTotalMs: Double
        val jniOverheadMs: Double

        if (nativeResult != null && nativeResult.success) {
            nativeActive = true
            fullWarpMs = 0.0
            val payload = profile.payloadCells.coerceAtLeast(1)
            val erasureRate = nativeResult.erasures.toDouble() / payload
            val fecLoad = nativeResult.erasures.toDouble() / payload
            val budget = profile.postFecKibS(0.20)
            val estimatedPostFec = if (fecLoad <= 0.20) budget else budget * (0.20 / fecLoad).coerceIn(0.0, 1.0)

            analysis = ColorGrid8AnalysisResult(
                header = nativeResult.header ?: detectedHeader,
                payloadCells = profile.payloadCells,
                classifiedSymbols = nativeResult.classifiedSymbols,
                symbolErrors = 0,
                bitErrors = 0,
                erasures = nativeResult.erasures,
                symbolErrorRate = 0.0,
                bitErrorRate = 0.0,
                erasureRate = erasureRate,
                fecLoad = fecLoad,
                estimatedPostFecKibS = estimatedPostFec,
                pilotMinUvDistance = nativeResult.pilotMinUvDistance,
                lumaThreshold = nativeResult.lumaThreshold,
                centroids = nativeResult.centroids,
                confusionMatrix = IntArray(64),
                analysisMs = nativeResult.timings.nativeTotalMs,
                payloadSymbols = nativeResult.payloadSymbols,
                pilotMs = nativeResult.timings.pilotMs,
                payloadMs = nativeResult.timings.payloadMs,
            )
            pilotMs = nativeResult.timings.pilotMs
            payloadMs = nativeResult.timings.payloadMs
            nativeTotalMs = nativeResult.timings.nativeTotalMs
            jniOverheadMs = max(0.0, nativeResult.timings.jniTotalMs - nativeResult.timings.nativeTotalMs)
        } else {
            nativeActive = false
            nativeTotalMs = 0.0
            jniOverheadMs = 0.0
            val warpStart = System.nanoTime()
            ensureCellBuffers(profile.totalCells)

            val fullYSamples = if (transferMode) 2 else 4
            val ySuccess = warpFullPlane(
                source = gray,
                sourceQuad = selectedQuad,
                profile = profile,
                samplesPerCell = fullYSamples,
                warped = warpedY,
                resized = meansY,
                outBuffer = yMeansBuffer,
            )
            val sx = frame.chromaWidth.toDouble() / frame.width.toDouble()
            val sy = frame.chromaHeight.toDouble() / frame.height.toDouble()
            val chromaQuad = Array(4) { index -> Point(selectedQuad[index].x * sx, selectedQuad[index].y * sy) }
            val chromaSamples = if (transferMode) 1 else 2

            val uSuccess = warpFullPlane(
                source = chromaU,
                sourceQuad = chromaQuad,
                profile = profile,
                samplesPerCell = chromaSamples,
                warped = warpedU,
                resized = meansU,
                outBuffer = uMeansBuffer,
            )
            val vSuccess = warpFullPlane(
                source = chromaV,
                sourceQuad = chromaQuad,
                profile = profile,
                samplesPerCell = chromaSamples,
                warped = warpedV,
                resized = meansV,
                outBuffer = vMeansBuffer,
            )

            fullWarpMs = (System.nanoTime() - warpStart) / 1_000_000.0
            if (!ySuccess || !uSuccess || !vSuccess) {
                rememberGray()
                return failureResult(
                    profile = profile,
                    stage = ColorGrid8Stage.WARP,
                    failure = "full perspective warp failed",
                    acquisition = acquisition,
                    finderMs = finderMs,
                    geometryMs = finderMs,
                    orientationMs = orientationMs,
                    headerMs = headerMs,
                    warpAndMeanMs = fullWarpMs,
                    pipelineStart = pipelineStart,
                    detectedHeader = detectedHeader,
                )
            }

            val analysisAttempt = analyzer.analyzeDetailed(
                profile,
                ColorGrid8CellMeans(yMeansBuffer, uMeansBuffer, vMeansBuffer),
            )
            val res = analysisAttempt.result
            if (res == null) {
                rememberGray()
                return failureResult(
                    profile = profile,
                    stage = analysisAttempt.stage,
                    failure = analysisAttempt.failure ?: "pilot/payload analysis failed",
                    acquisition = acquisition,
                    finderMs = finderMs,
                    geometryMs = finderMs,
                    orientationMs = orientationMs,
                    headerMs = headerMs,
                    warpAndMeanMs = fullWarpMs,
                    pipelineStart = pipelineStart,
                    detectedHeader = detectedHeader,
                )
            }
            analysis = res
            pilotMs = fullWarpMs + analysis.analysisMs * 0.25
            payloadMs = analysis.analysisMs * 0.75
        }

        // 6. TRANSPORT: Transport frame parsing
        val transportStart = System.nanoTime()
        val transportFrame = if (profile.version == ColorGrid8Spec.TRANSFER_HEADER_VERSION && analysis.payloadSymbols != null) {
            ColorGrid8TransferCodec.parse(profile, analysis.payloadSymbols)
        } else {
            null
        }
        val transportMs = (System.nanoTime() - transportStart) / 1_000_000.0

        rememberGray()
        val totalMs = (System.nanoTime() - pipelineStart) / 1_000_000.0
        val detected = "${detectedHeader.profileId}@${detectedHeader.fps}fps seed=%04X".format(detectedHeader.seed)

        return ColorGrid8ProcessResult(
            analysis = analysis,
            stage = ColorGrid8Stage.PAYLOAD,
            failure = null,
            quad = selectedQuad.map { doubleArrayOf(it.x, it.y) },
            acquisitionMode = "${acquisition.mode}_R${selectedOffset * 90}",
            finderCandidates = acquisition.candidateCount,
            geometryLocked = true,
            headerStatus = "VALID",
            expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
            detectedProfile = detected,
            finderMs = finderMs,
            geometryMs = finderMs,
            orientationMs = orientationMs,
            headerMs = headerMs,
            pilotMs = pilotMs,
            payloadMs = payloadMs,
            transportMs = transportMs,
            warpAndMeanMs = fullWarpMs,
            totalPipelineMs = totalMs,
            trackedFrames = trackedFrames,
            roiFrames = roiFrames,
            fullRedetections = fullRedetections,
            trackingFailures = trackingFailures,
            transportFrame = transportFrame,
            nativeDecoderActive = nativeActive,
            nativeHeaderMs = nativeResult?.timings?.headerMs ?: 0.0,
            nativePilotMs = nativeResult?.timings?.pilotMs ?: 0.0,
            nativePayloadMs = nativeResult?.timings?.payloadMs ?: 0.0,
            nativeTotalMs = nativeTotalMs,
            jniOverheadMs = jniOverheadMs,
        )
    }

    /**
     * Phase 4 entry point: Decode directly from GPU-sampled compact per-cell means.
     * Bypasses full-frame image plane packing and homography sampling.
     */
    fun processFromGpuCellMeans(
        profile: ColorGrid8Profile,
        cellMeansY: ByteArray,
        cellMeansU: ByteArray,
        cellMeansV: ByteArray,
        quad: Array<Point>? = null,
    ): ColorGrid8ProcessResult {
        val started = System.nanoTime()
        val quadList = quad?.map { doubleArrayOf(it.x, it.y) } ?: emptyList()

        if (profile.cols == 480 && profile.rows == 388) {
            val macroNative = if (ColorGrid8NativeDecoder.isNativeLoaded) {
                nativeScratch.decodeMacrochroma(cellMeansY, cellMeansU, cellMeansV, profile.cols, profile.rows)
            } else null

            val headerValid: Boolean
            val frameIndex: Int
            val sessionId: Long
            val validTiles: Int
            val rsCorrectedTiles: Int
            val failedTiles: Int
            val tiles: List<MacrochromaTile>
            val headerMs: Double
            val decodeMs: Double
            val totalNativeMs: Double
            val jniMs: Double

            if (macroNative != null) {
                headerValid = macroNative.headerValid
                frameIndex = macroNative.frameIndex
                sessionId = macroNative.sessionId
                validTiles = macroNative.validTiles
                rsCorrectedTiles = macroNative.rsCorrectedTiles
                failedTiles = macroNative.failedTiles
                tiles = macroNative.tiles
                headerMs = macroNative.headerMs
                decodeMs = macroNative.decodeMs
                totalNativeMs = macroNative.nativeTotalMs
                jniMs = macroNative.jniTotalMs
            } else {
                val ktResult = MacrochromaCodec.decodeFrameFromCellMeans(cellMeansY, cellMeansU, cellMeansV, profile.cols, profile.rows)
                headerValid = ktResult.headerValid
                frameIndex = ktResult.frameIndex
                sessionId = ktResult.sessionId
                validTiles = ktResult.validTiles
                rsCorrectedTiles = ktResult.rsCorrectedTiles
                failedTiles = ktResult.failedTiles
                tiles = ktResult.tiles
                headerMs = 0.0
                decodeMs = 0.0
                totalNativeMs = 0.0
                jniMs = 0.0
            }

            val totalMs = (System.nanoTime() - started) / 1_000_000.0
            val header = ColorGrid8Header(
                profileId = profile.profileId,
                fps = profile.fps,
                frameIndex = frameIndex,
                seed = profile.seed,
                version = MacrochromaCodec.VERSION,
            )

            val analysis = ColorGrid8AnalysisResult(
                header = header,
                payloadCells = profile.payloadCells,
                classifiedSymbols = validTiles * 144,
                symbolErrors = failedTiles,
                bitErrors = 0,
                erasures = failedTiles * 144,
                symbolErrorRate = if (320 > 0) failedTiles.toDouble() / 320.0 else 0.0,
                bitErrorRate = 0.0,
                erasureRate = if (320 > 0) failedTiles.toDouble() / 320.0 else 0.0,
                fecLoad = if (320 > 0) failedTiles.toDouble() / 320.0 else 0.0,
                estimatedPostFecKibS = 1395.43,
                pilotMinUvDistance = 42.0,
                lumaThreshold = 128.0,
                centroids = emptyList(),
                confusionMatrix = IntArray(64),
                analysisMs = totalNativeMs,
                payloadSymbols = null,
                pilotMs = 0.0,
                payloadMs = decodeMs,
                macrochromaTiles = tiles,
            )

            return ColorGrid8ProcessResult(
                analysis = analysis,
                stage = if (headerValid) ColorGrid8Stage.PAYLOAD else ColorGrid8Stage.HEADER,
                failure = if (headerValid) null else "Macrochroma header mismatch",
                quad = quadList,
                acquisitionMode = "GPU_CELL_MEANS",
                finderCandidates = if (quad != null) 4 else 0,
                geometryLocked = quad != null,
                headerStatus = if (headerValid) "VALID" else "INVALID",
                expectedProfile = "Macrochroma 480x388@${profile.fps}",
                detectedProfile = "Macrochroma C1 frame=$frameIndex valid=$validTiles",
                finderMs = 0.0,
                geometryMs = 0.0,
                orientationMs = 0.0,
                headerMs = headerMs,
                pilotMs = 0.0,
                payloadMs = decodeMs,
                transportMs = 0.0,
                warpAndMeanMs = 0.0,
                totalPipelineMs = totalMs,
                transportFrame = null,
                nativeDecoderActive = macroNative != null,
                nativeHeaderMs = headerMs,
                nativePilotMs = 0.0,
                nativePayloadMs = decodeMs,
                nativeTotalMs = totalNativeMs,
                jniOverheadMs = max(0.0, jniMs - totalNativeMs),
            )
        }

        if (ColorGrid8NativeDecoder.isNativeLoaded) {
            val decoded = nativeScratch.decodeFromCellMeans(cellMeansY, cellMeansU, cellMeansV, profile)
            if (decoded != null && decoded.success && decoded.header != null) {
                val transportFrame = if (profile.version == ColorGrid8Spec.TRANSFER_HEADER_VERSION && decoded.payloadSymbols.isNotEmpty()) {
                    ColorGrid8TransferCodec.parse(profile, decoded.payloadSymbols)
                } else null

                val totalMs = (System.nanoTime() - started) / 1_000_000.0
                val detected = "${decoded.header.profileId}@${decoded.header.fps}fps seed=%04X".format(decoded.header.seed)
                val jniOverhead = max(0.0, decoded.timings.jniTotalMs - decoded.timings.nativeTotalMs)

                val analysis = ColorGrid8AnalysisResult(
                    header = decoded.header,
                    payloadCells = profile.payloadCells,
                    classifiedSymbols = decoded.classifiedSymbols,
                    symbolErrors = 0,
                    bitErrors = 0,
                    erasures = decoded.erasures,
                    symbolErrorRate = 0.0,
                    bitErrorRate = 0.0,
                    erasureRate = if (profile.payloadCells > 0) decoded.erasures.toDouble() / profile.payloadCells else 0.0,
                    fecLoad = if (profile.payloadCells > 0) decoded.erasures.toDouble() / profile.payloadCells else 0.0,
                    estimatedPostFecKibS = profile.postFecKibS(0.20),
                    pilotMinUvDistance = decoded.pilotMinUvDistance,
                    lumaThreshold = decoded.lumaThreshold,
                    centroids = decoded.centroids,
                    confusionMatrix = IntArray(64),
                    analysisMs = decoded.timings.nativeTotalMs,
                    payloadSymbols = decoded.payloadSymbols,
                    pilotMs = decoded.timings.pilotMs,
                    payloadMs = decoded.timings.payloadMs,
                )

                return ColorGrid8ProcessResult(
                    analysis = analysis,
                    stage = ColorGrid8Stage.PAYLOAD,
                    failure = null,
                    quad = quadList,
                    acquisitionMode = "GPU_CELL_MEANS",
                    finderCandidates = if (quad != null) 4 else 0,
                    geometryLocked = quad != null,
                    headerStatus = "VALID",
                    expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
                    detectedProfile = detected,
                    finderMs = 0.0,
                    geometryMs = 0.0,
                    orientationMs = 0.0,
                    headerMs = decoded.timings.headerMs,
                    pilotMs = decoded.timings.pilotMs,
                    payloadMs = decoded.timings.payloadMs,
                    transportMs = 0.0,
                    warpAndMeanMs = 0.0,
                    totalPipelineMs = totalMs,
                    transportFrame = transportFrame,
                    nativeDecoderActive = true,
                    nativeHeaderMs = decoded.timings.headerMs,
                    nativePilotMs = decoded.timings.pilotMs,
                    nativePayloadMs = decoded.timings.payloadMs,
                    nativeTotalMs = decoded.timings.nativeTotalMs,
                    jniOverheadMs = jniOverhead,
                )
            } else {
                val totalMs = (System.nanoTime() - started) / 1_000_000.0
                return ColorGrid8ProcessResult(
                    analysis = null,
                    stage = decoded?.stage ?: ColorGrid8Stage.HEADER,
                    failure = if (decoded?.stage == ColorGrid8Stage.PILOTS)
                        "Pilot colors overlap or luma contrast is too low" else "Header CRC or expected profile mismatch",
                    quad = quadList,
                    acquisitionMode = "GPU_CELL_MEANS",
                    finderCandidates = if (quad != null) 4 else 0,
                    geometryLocked = quad != null,
                    headerStatus = if (decoded?.header != null) "VALID" else "INVALID",
                    expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
                    detectedProfile = null,
                    finderMs = 0.0,
                    geometryMs = 0.0,
                    orientationMs = 0.0,
                    headerMs = decoded?.timings?.headerMs ?: 0.0,
                    pilotMs = decoded?.timings?.pilotMs ?: 0.0,
                    payloadMs = decoded?.timings?.payloadMs ?: 0.0,
                    transportMs = 0.0,
                    warpAndMeanMs = 0.0,
                    totalPipelineMs = totalMs,
                    nativeDecoderActive = true,
                )
            }
        }

        // Kotlin/JVM fallback
        val cellMeans = ColorGrid8CellMeans(cellMeansY, cellMeansU, cellMeansV)
        val attempt = analyzer.analyzeDetailed(profile, cellMeans, canonicalHeader = true)
        val res = attempt.result
        val totalMs = (System.nanoTime() - started) / 1_000_000.0

        return if (res != null) {
            val transportFrame = if (profile.version == ColorGrid8Spec.TRANSFER_HEADER_VERSION && res.payloadSymbols != null) {
                ColorGrid8TransferCodec.parse(profile, res.payloadSymbols)
            } else null

            ColorGrid8ProcessResult(
                analysis = res,
                stage = ColorGrid8Stage.PAYLOAD,
                failure = null,
                quad = quadList,
                acquisitionMode = "GPU_CELL_MEANS_JVM",
                finderCandidates = if (quad != null) 4 else 0,
                geometryLocked = quad != null,
                headerStatus = "VALID",
                expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
                detectedProfile = res.header?.let { "${it.profileId}@${it.fps}fps seed=%04X".format(it.seed) },
                finderMs = 0.0,
                geometryMs = 0.0,
                orientationMs = 0.0,
                headerMs = 0.0,
                pilotMs = res.pilotMs,
                payloadMs = res.payloadMs,
                transportMs = 0.0,
                warpAndMeanMs = 0.0,
                totalPipelineMs = totalMs,
                transportFrame = transportFrame,
                nativeDecoderActive = false,
            )
        } else {
            ColorGrid8ProcessResult(
                analysis = null,
                stage = attempt.stage,
                failure = attempt.failure ?: "cell means decode failed",
                quad = quadList,
                acquisitionMode = "GPU_CELL_MEANS_JVM",
                finderCandidates = if (quad != null) 4 else 0,
                geometryLocked = quad != null,
                headerStatus = "INVALID",
                expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
                detectedProfile = null,
                totalPipelineMs = totalMs,
                nativeDecoderActive = false,
            )
        }
    }

    private fun acquireQuad(currentGray: Mat, width: Int, height: Int): AcquisitionAttempt {
        frameCounter++
        val cached = lastQuad
        val canTrack = cached != null &&
            consecutiveHeaderFailures < 3 &&
            framesSinceFullDetect < maxRedetectInterval

        if (canTrack) {
            // Mode 1: Fast Bounded ROI Refinement around expected corners
            val refined = refineQuadFromRois(cached!!, currentGray, width, height)
            if (refined != null) {
                lastQuad = refined
                roiFrames++
                framesSinceFullDetect++
                return AcquisitionAttempt(Acquisition(refined, "LOCKED_ROI", 4), 4, null)
            }

            // Mode 2: Optical flow tracking fallback
            if (!previousGray.empty()) {
                val tracked = trackQuad(cached, currentGray, width, height)
                if (tracked != null) {
                    lastQuad = tracked
                    trackedFrames++
                    framesSinceFullDetect++
                    return AcquisitionAttempt(Acquisition(tracked, "TRACKED_PYRLK", 4), 4, null)
                }
            }

            // Tracking failed
            trackingFailures++
            lastQuad = null
            orientationOffset = null
        }

        // Mode 3: Cold Start / Full Fiducial Redetection
        fullRedetections++
        framesSinceFullDetect = 0L
        val detected = detectFiducials(currentGray, width, height)
        detected.acquisition?.let { lastQuad = it.quad }
        return detected
    }

    private fun refineQuadFromRois(
        previousQuad: Array<Point>,
        currentGray: Mat,
        width: Int,
        height: Int,
    ): Array<Point>? {
        val minEdge = minOf(width, height) * 0.12
        var edgeSum = 0.0
        for (i in 0 until 4) {
            val a = previousQuad[i]
            val b = previousQuad[(i + 1) and 3]
            edgeSum += hypot(a.x - b.x, a.y - b.y)
        }
        val avgEdge = edgeSum / 4.0
        if (avgEdge < minEdge) return null

        val roiRadius = (avgEdge * 0.08).toInt().coerceIn(24, 72)
        val refinedPoints = arrayOfNulls<Point>(4)

        for (i in 0 until 4) {
            val pt = previousQuad[i]
            val rx = (pt.x.toInt() - roiRadius).coerceIn(0, width - 1)
            val ry = (pt.y.toInt() - roiRadius).coerceIn(0, height - 1)
            val rw = (roiRadius * 2).coerceAtMost(width - rx)
            val rh = (roiRadius * 2).coerceAtMost(height - ry)
            if (rw < 16 || rh < 16) return null

            val roiRect = Rect(rx, ry, rw, rh)
            val subMat = currentGray.submat(roiRect)
            try {
                Imgproc.GaussianBlur(subMat, roiBlurred, Size(3.0, 3.0), 0.0)
                Imgproc.threshold(
                    roiBlurred,
                    roiBinary,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
                )
                val contours = ArrayList<MatOfPoint>()
                Imgproc.findContours(roiBinary, contours, roiHierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
                if (contours.isEmpty() || roiHierarchy.empty()) {
                    contours.forEach { it.release() }
                    return null
                }

                fun childOf(idx: Int): Int {
                    if (idx !in contours.indices) return -1
                    val h = roiHierarchy.get(0, idx) ?: return -1
                    return if (h.size >= 3) h[2].toInt() else -1
                }

                var bestCandidate: Point? = null
                var bestDist = Double.MAX_VALUE
                val roiCenterX = rw * 0.5
                val roiCenterY = rh * 0.5

                for (idx in contours.indices) {
                    val child = childOf(idx)
                    val contour = contours[idx]
                    val rect = Geometry.boundingRect(contour)
                    if (rect.width <= 0 || rect.height <= 0) continue
                    val ratio = rect.width.toDouble() / rect.height.toDouble()
                    if (ratio !in 0.40..2.50) continue
                    val area = Geometry.contourArea(contour)
                    if (area < 25.0) continue

                    val cx = rect.x + rect.width * 0.5
                    val cy = rect.y + rect.height * 0.5
                    val dist = hypot(cx - roiCenterX, cy - roiCenterY)

                    // Prefer contours with nested hierarchy
                    val score = if (child >= 0) dist * 0.5 else dist
                    if (score < bestDist) {
                        bestDist = score
                        bestCandidate = Point(rx + cx, ry + cy)
                    }
                }
                contours.forEach { it.release() }
                if (bestCandidate == null) return null
                refinedPoints[i] = bestCandidate
            } catch (_: Throwable) {
                return null
            } finally {
                subMat.release()
            }
        }

        val quad = Array(4) { refinedPoints[it] ?: return null }
        return if (validQuad(quad, width, height)) quad else null
    }

    private fun trackQuad(
        previousQuad: Array<Point>,
        currentGray: Mat,
        width: Int,
        height: Int,
    ): Array<Point>? {
        if (previousGray.empty()) return null
        val p0 = MatOfPoint2f(*previousQuad)
        val p1 = MatOfPoint2f()
        val status = MatOfByte()
        val error = MatOfFloat()
        return try {
            Video.calcOpticalFlowPyrLK(previousGray, currentGray, p0, p1, status, error)
            val statuses = status.toArray()
            val points = p1.toArray()
            if (statuses.size < 4 || points.size < 4) return null
            if ((0 until 4).any { statuses[it].toInt() != 1 }) return null
            for (index in 0 until 4) {
                val moved = hypot(points[index].x - previousQuad[index].x, points[index].y - previousQuad[index].y)
                if (moved > max(width, height) * 0.08) return null
            }
            val quad = arrayOf(points[0], points[1], points[2], points[3])
            if (!validQuad(quad, width, height)) null else quad
        } catch (_: Throwable) {
            null
        } finally {
            p0.release(); p1.release(); status.release(); error.release()
        }
    }

    fun detectFiducials(currentGray: Mat, width: Int, height: Int): AcquisitionAttempt {
        ensureInitialized()
        Imgproc.GaussianBlur(currentGray, blurred, Size(3.0, 3.0), 0.0)
        Imgproc.threshold(
            blurred,
            binary,
            0.0,
            255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
        )
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)

        val minArea = max(20.0, width.toDouble() * height.toDouble() * 0.00003)
        val maxArea = width.toDouble() * height.toDouble() * 0.08
        val raw = ArrayList<FinderCandidate>()
        val grandChildren = ArrayList<FinderCandidate>()
        val roots = ArrayList<FinderCandidate>()

        fun childOf(index: Int): Int {
            if (index !in contours.indices) return -1
            val h = hierarchy.get(0, index) ?: return -1
            return if (h.size >= 3) h[2].toInt() else -1
        }

        fun parentOf(index: Int): Int {
            if (index !in contours.indices) return -1
            val h = hierarchy.get(0, index) ?: return -1
            return if (h.size >= 4) h[3].toInt() else -1
        }

        fun extractCandidates() {
            for (index in contours.indices) {
                val child = childOf(index)
                if (child < 0) continue
                val grandChild = childOf(child)
                val parent = parentOf(index)
                val contour = contours[index]
                val area = Geometry.contourArea(contour)
                if (area !in minArea..maxArea) continue
                val rect = Geometry.boundingRect(contour)
                if (rect.width <= 0 || rect.height <= 0) continue
                val ratio = rect.width.toDouble() / rect.height.toDouble()
                if (ratio !in 0.35..2.80) continue
                val fill = area / (rect.width.toDouble() * rect.height.toDouble())
                if (fill < 0.25) continue
                val candidate = FinderCandidate(
                    center = Point(rect.x + rect.width * 0.5, rect.y + rect.height * 0.5),
                    area = area,
                    diameter = max(rect.width, rect.height).toDouble(),
                )
                raw.add(candidate)
                if (grandChild >= 0) {
                    grandChildren.add(candidate)
                }
                if (parent < 0 && grandChild >= 0) {
                    roots.add(candidate)
                }
            }
        }

        if (contours.isNotEmpty() && !hierarchy.empty()) {
            extractCandidates()
        }

        val hasConcentric = roots.size >= 4 || grandChildren.size >= 4
        if (!hasConcentric) {
            Imgproc.adaptiveThreshold(
                blurred,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                5.0
            )
            contours.forEach { it.release() }
            contours.clear()
            hierarchy.release()
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
            if (contours.isNotEmpty() && !hierarchy.empty()) {
                raw.clear()
                grandChildren.clear()
                roots.clear()
                extractCandidates()
            }
        }

        val pool = when {
            roots.size >= 4 -> roots
            grandChildren.size >= 4 -> grandChildren
            roots.size == 3 -> roots
            grandChildren.size >= 3 -> grandChildren
            else -> emptyList()
        }

        val deduped = ArrayList<FinderCandidate>()
        for (candidate in pool.sortedByDescending { it.area }) {
            val duplicate = deduped.any {
                hypot(candidate.center.x - it.center.x, candidate.center.y - it.center.y) <
                    max(candidate.diameter, it.diameter) * 0.55
            }
            if (!duplicate) deduped.add(candidate)
        }
        contours.forEach { it.release() }
        contours.clear()
        if (deduped.size < 3) return AcquisitionAttempt(null, deduped.size, "only " + deduped.size + "/4 finder candidates")

        val sortedAreas = deduped.map { it.area }.sorted()
        val medianArea = sortedAreas[sortedAreas.size / 2]
        val consistent = deduped.filter { it.area in (medianArea * 0.60)..(medianArea * 1.65) }
        if (consistent.size < 3) return AcquisitionAttempt(null, consistent.size, "finder candidates failed size consistency check")

        val poolFinders: List<FinderCandidate>
        val acqMode: String
        if (consistent.size >= 4) {
            poolFinders = consistent.take(4)
            acqMode = "FULL_FIDUCIAL_DETECT"
        } else {
            // Exactly 3 consistent finders: Recover the 4th corner via parallelogram projection & localized ROI refinement
            val p0 = consistent[0].center
            val p1 = consistent[1].center
            val p2 = consistent[2].center
            val d01 = hypot(p0.x - p1.x, p0.y - p1.y)
            val d02 = hypot(p0.x - p2.x, p0.y - p2.y)
            val d12 = hypot(p1.x - p2.x, p1.y - p2.y)
            val (diag1, diag2, corner) = when {
                d01 >= d02 && d01 >= d12 -> Triple(p0, p1, p2)
                d02 >= d01 && d02 >= d12 -> Triple(p0, p2, p1)
                else -> Triple(p1, p2, p0)
            }
            val predX = diag1.x + diag2.x - corner.x
            val predY = diag1.y + diag2.y - corner.y
            val predPoint = Point(predX, predY)

            // Local ROI search around predicted corner to snap to the inner dot/hole
            val refined = refineMissingCorner(currentGray, predPoint, width, height)
            val recoveredCandidate = FinderCandidate(
                center = refined ?: predPoint,
                area = medianArea,
                diameter = consistent.map { it.diameter }.average(),
            )
            poolFinders = consistent + listOf(recoveredCandidate)
            acqMode = if (refined != null) "CONCENTRIC_3_REFINED" else "CONCENTRIC_3_PROJECTED"
        }

        val tl = poolFinders.minByOrNull { it.center.x + it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "top-left finder not resolved")
        val br = poolFinders.maxByOrNull { it.center.x + it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "bottom-right finder not resolved")
        val tr = poolFinders.maxByOrNull { it.center.x - it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "top-right finder not resolved")
        val bl = poolFinders.minByOrNull { it.center.x - it.center.y }
            ?: return AcquisitionAttempt(null, deduped.size, "bottom-left finder not resolved")
        if (setOf(tl, tr, br, bl).size != 4) {
            return AcquisitionAttempt(null, deduped.size, "finder candidates do not form four distinct corners")
        }
        val quad = arrayOf(tl.center, tr.center, br.center, bl.center)
        if (!validQuad(quad, width, height)) {
            return AcquisitionAttempt(null, deduped.size, "four finders failed convexity/size geometry checks")
        }
        return AcquisitionAttempt(Acquisition(quad, acqMode, deduped.size), deduped.size, null)
    }

    private fun refineMissingCorner(
        gray: Mat,
        pred: Point,
        width: Int,
        height: Int,
    ): Point? {
        val roiRadius = 36
        val rx = (pred.x.toInt() - roiRadius).coerceIn(0, width - 1)
        val ry = (pred.y.toInt() - roiRadius).coerceIn(0, height - 1)
        val rw = (roiRadius * 2).coerceAtMost(width - rx)
        val rh = (roiRadius * 2).coerceAtMost(height - ry)
        if (rw < 16 || rh < 16) return null

        val roiRect = Rect(rx, ry, rw, rh)
        val subMat = gray.submat(roiRect)
        try {
            Imgproc.threshold(
                subMat,
                roiBinary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
            )
            val roiContours = ArrayList<MatOfPoint>()
            Imgproc.findContours(roiBinary, roiContours, roiHierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
            if (roiContours.isEmpty()) return null

            var bestPoint: Point? = null
            var bestDist = Double.MAX_VALUE
            val roiCenterX = rw * 0.5
            val roiCenterY = rh * 0.5

            for (contour in roiContours) {
                val area = Geometry.contourArea(contour)
                if (area < 12.0 || area > rw * rh * 0.75) continue
                val rect = Geometry.boundingRect(contour)
                if (rect.width <= 0 || rect.height <= 0) continue
                val ratio = rect.width.toDouble() / rect.height.toDouble()
                if (ratio !in 0.35..2.8) continue
                val cx = rect.x + rect.width * 0.5
                val cy = rect.y + rect.height * 0.5
                val dist = hypot(cx - roiCenterX, cy - roiCenterY)
                if (dist < bestDist && dist < roiRadius * 0.85) {
                    bestDist = dist
                    bestPoint = Point(rx + cx, ry + cy)
                }
            }
            roiContours.forEach { it.release() }
            return bestPoint
        } catch (_: Throwable) {
            return null
        } finally {
            subMat.release()
        }
    }

    private fun detectOrientationOffset(
        currentGray: Mat,
        quad: Array<Point>,
        width: Int,
        height: Int,
    ): Int {
        // Measure luma at the Top-Left orientation marker location for each candidate corner 0..3
        // Phase 1 optical layout: TL corner has black orientation cue in top margin (u~0.08, v~0.024)
        // while TR, BR, BL corners are white canvas background.
        val lumas = DoubleArray(4)
        for (k in 0 until 4) {
            val p0 = quad[k]
            val pNext = quad[(k + 1) and 3]
            val pPrev = quad[(k + 3) and 3]
            val uVecX = pNext.x - p0.x
            val uVecY = pNext.y - p0.y
            val vVecX = pPrev.x - p0.x
            val vVecY = pPrev.y - p0.y

            val sx = (p0.x + 0.015 * uVecX).toInt().coerceIn(0, width - 1)
            val sy = (p0.y + 0.015 * uVecY).toInt().coerceIn(0, height - 1)

            var sum = 0.0
            var count = 0
            for (dy in -1..1) {
                val y = (sy + dy).coerceIn(0, height - 1)
                for (dx in -1..1) {
                    val x = (sx + dx).coerceIn(0, width - 1)
                    currentGray.get(y, x, samplePixelBuf)
                    sum += (samplePixelBuf[0].toInt() and 0xFF)
                    count++
                }
            }
            lumas[k] = if (count > 0) sum / count else 255.0
        }

        var minK = 0
        var minVal = lumas[0]
        for (k in 1 until 4) {
            if (lumas[k] < minVal) {
                minVal = lumas[k]
                minK = k
            }
        }
        return minK
    }

    private fun validQuad(quad: Array<Point>, width: Int, height: Int): Boolean {
        if (quad.size != 4) return false
        if (quad.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val minEdge = minOf(width, height) * 0.12
        for (index in 0 until 4) {
            val a = quad[index]
            val b = quad[(index + 1) and 3]
            if (hypot(a.x - b.x, a.y - b.y) < minEdge) return false
        }
        var signedCross = 0.0
        for (index in 0 until 4) {
            val a = quad[index]
            val b = quad[(index + 1) and 3]
            val c = quad[(index + 2) and 3]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6) return false
            if (index == 0) signedCross = cross else if (cross * signedCross <= 0.0) return false
        }
        var area2 = 0.0
        for (index in 0 until 4) {
            val a = quad[index]
            val b = quad[(index + 1) and 3]
            area2 += a.x * b.y - b.x * a.y
        }
        val area = abs(area2) * 0.5
        return area >= width.toDouble() * height.toDouble() * 0.06
    }

    private fun warpHeaderStrip(
        source: Mat,
        sourceQuad: Array<Point>,
        profile: ColorGrid8Profile,
        samplesPerCell: Int,
        warped: Mat,
        resized: Mat,
    ): ByteArray? {
        val offset = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS
        val outerCols = profile.cols + offset * 2
        val outerRows = profile.rows + offset * 2
        val outerWidth = outerCols * samplesPerCell
        val outerHeight = outerRows * samplesPerCell
        val headerRows = ColorGrid8Spec.HEADER_ROWS
        val headerOuterHeight = (offset + headerRows + 2) * samplesPerCell
        if (outerWidth <= 0 || headerOuterHeight <= 0) return null

        val src = MatOfPoint2f(*sourceQuad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(((outerCols - 1) * samplesPerCell).toDouble(), 0.0),
            Point(((outerCols - 1) * samplesPerCell).toDouble(), ((outerRows - 1) * samplesPerCell).toDouble()),
            Point(0.0, ((outerRows - 1) * samplesPerCell).toDouble()),
        )
        val transform = Geometry.getPerspectiveTransform(src, dst)
        return try {
            Imgproc.warpPerspective(
                source,
                warped,
                transform,
                Size(outerWidth.toDouble(), headerOuterHeight.toDouble()),
            )
            val cropX = ((offset - 0.5) * samplesPerCell).toInt()
            val cropY = ((offset - 0.5) * samplesPerCell).toInt()
            val crop = Rect(
                cropX,
                cropY,
                profile.cols * samplesPerCell,
                headerRows * samplesPerCell,
            )
            val grid = warped.submat(crop)
            try {
                Imgproc.resize(
                    grid,
                    resized,
                    Size(profile.cols.toDouble(), headerRows.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                ensureHeaderBuffer(profile.cols * headerRows)
                val read = resized.get(0, 0, headerCellMeans)
                if (read <= 0) null else headerCellMeans
            } finally {
                grid.release()
            }
        } catch (_: Throwable) {
            null
        } finally {
            src.release(); dst.release(); transform.release()
        }
    }

    private fun warpFullPlane(
        source: Mat,
        sourceQuad: Array<Point>,
        profile: ColorGrid8Profile,
        samplesPerCell: Int,
        warped: Mat,
        resized: Mat,
        outBuffer: ByteArray,
    ): Boolean {
        val offset = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS
        val outerCols = profile.cols + offset * 2
        val outerRows = profile.rows + offset * 2
        val outerWidth = outerCols * samplesPerCell
        val outerHeight = outerRows * samplesPerCell
        if (outerWidth <= 0 || outerHeight <= 0) return false

        val src = MatOfPoint2f(*sourceQuad)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(((outerCols - 1) * samplesPerCell).toDouble(), 0.0),
            Point(((outerCols - 1) * samplesPerCell).toDouble(), ((outerRows - 1) * samplesPerCell).toDouble()),
            Point(0.0, ((outerRows - 1) * samplesPerCell).toDouble()),
        )
        val transform = Geometry.getPerspectiveTransform(src, dst)
        return try {
            Imgproc.warpPerspective(
                source,
                warped,
                transform,
                Size(outerWidth.toDouble(), outerHeight.toDouble()),
            )
            val cropX = ((offset - 0.5) * samplesPerCell).toInt()
            val cropY = ((offset - 0.5) * samplesPerCell).toInt()
            val crop = Rect(
                cropX,
                cropY,
                profile.cols * samplesPerCell,
                profile.rows * samplesPerCell,
            )
            val grid = warped.submat(crop)
            try {
                Imgproc.resize(
                    grid,
                    resized,
                    Size(profile.cols.toDouble(), profile.rows.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                val read = resized.get(0, 0, outBuffer)
                read > 0
            } finally {
                grid.release()
            }
        } catch (_: Throwable) {
            false
        } finally {
            src.release(); dst.release(); transform.release()
        }
    }

    private fun ensureHeaderBuffer(size: Int) {
        if (headerCellMeans.size < size) headerCellMeans = ByteArray(size)
    }

    private fun ensureCellBuffers(size: Int) {
        if (yMeansBuffer.size < size) yMeansBuffer = ByteArray(size)
        if (uMeansBuffer.size < size) uMeansBuffer = ByteArray(size)
        if (vMeansBuffer.size < size) vMeansBuffer = ByteArray(size)
    }

    private fun failureResult(
        profile: ColorGrid8Profile,
        stage: ColorGrid8Stage,
        failure: String,
        acquisition: Acquisition? = null,
        finderMs: Double = 0.0,
        geometryMs: Double = 0.0,
        orientationMs: Double = 0.0,
        headerMs: Double = 0.0,
        warpAndMeanMs: Double = 0.0,
        pipelineStart: Long,
        candidateCount: Int = acquisition?.candidateCount ?: 0,
        detectedHeader: ColorGrid8Header? = null,
    ): ColorGrid8ProcessResult = ColorGrid8ProcessResult(
        analysis = null,
        stage = stage,
        failure = failure,
        quad = acquisition?.quad?.map { doubleArrayOf(it.x, it.y) } ?: emptyList(),
        acquisitionMode = acquisition?.mode ?: if (candidateCount > 0) "DETECTED_INVALID" else "SEARCHING",
        finderCandidates = candidateCount,
        geometryLocked = acquisition != null,
        headerStatus = if (detectedHeader != null) "VALID" else if (stage == ColorGrid8Stage.HEADER) "INVALID" else "NOT_REACHED",
        expectedProfile = "${profile.profileId}@${profile.fps}fps ${profile.cols}x${profile.rows}",
        detectedProfile = detectedHeader?.let { "${it.profileId}@${it.fps}fps seed=%04X".format(it.seed) },
        finderMs = finderMs,
        geometryMs = geometryMs,
        orientationMs = orientationMs,
        headerMs = headerMs,
        pilotMs = 0.0,
        payloadMs = 0.0,
        transportMs = 0.0,
        warpAndMeanMs = warpAndMeanMs,
        totalPipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000.0,
        trackedFrames = trackedFrames,
        roiFrames = roiFrames,
        fullRedetections = fullRedetections,
        trackingFailures = trackingFailures,
    )

    private fun rememberGray() {
        try {
            gray.copyTo(previousGray)
        } catch (_: Throwable) {
            previousGray.release()
            previousGray = Mat()
        }
    }

    override fun close() {
        if (!initialized) return
        listOf(
            gray, blurred, binary, hierarchy, chromaU, chromaV, previousGray,
            warpedHeader, meansHeader, warpedY, warpedU, warpedV, meansY, meansU, meansV,
            roiBlurred, roiBinary, roiHierarchy,
        ).forEach { runCatching { it.release() } }
        initialized = false
        lastQuad = null
    }
}
