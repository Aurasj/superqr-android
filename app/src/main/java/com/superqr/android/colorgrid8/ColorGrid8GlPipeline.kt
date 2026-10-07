package com.superqr.android.colorgrid8

import android.graphics.SurfaceTexture
import com.superqr.android.camera.AnalysisRateAccumulator
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8FrameProcessor
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Analyzer
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8ProcessResult
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Stage
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8NativeDecoder
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8TransferCodec
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlThread
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlSampler
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8HeaderSearch
import com.superqr.android.vision.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class GlPipelineResult(
    val processResult: ColorGrid8ProcessResult?,
    val cameraTimestampNs: Long,
    val gpuSubmitNs: Long,
    val gpuReadyNs: Long,
    val decodeStartNs: Long,
    val decodeEndNs: Long,
    val gpuSampleMs: Double,
    val gpuReadbackMs: Double,
    val nativeDecodeMs: Double,
    val fullPipelineMs: Double,
    val cameraFps: Double,
    val cameraPath: String,  // "CAMERA2_GL"
    val gpuPath: String,     // "ON" or "OFF"
    val nativeDecoder: String, // "ON (ARM64 NEON)" or "OFF"
    val sampleMode: String,  // "1x" / "2x2" / "3x3"
    val resolution: String,  // e.g. "3840x2160"
)

class ColorGrid8GlPipeline(
    private val glThread: ColorGrid8GlThread,
    private val analysisExecutor: ExecutorService
) : SurfaceTexture.OnFrameAvailableListener {

    var profile: ColorGrid8Profile = ColorGrid8Profile(
        336, 288, 60, version = ColorGrid8Spec.TRANSFER_HEADER_VERSION
    )
    var sampleMode: Int = 0
    var onResult: ((GlPipelineResult) -> Unit)? = null
    var onPreviewFrame: ((ByteArray, Int, Int) -> Unit)? = null

    private var lastPreviewTimeNs = 0L
    private val previewIntervalNs = 125_000_000L // 8 FPS throttled preview (zero performance drop)
    private val previewBuffer = ByteArray(ColorGrid8GlSampler.FINDER_WIDTH * ColorGrid8GlSampler.FINDER_HEIGHT * 4)

    private val glSampler = ColorGrid8GlSampler()
    private val processor = ColorGrid8FrameProcessor(maxRedetectInterval = 60)
    private val headerAnalyzer = ColorGrid8Analyzer()
    private val rateAccumulator = AnalysisRateAccumulator(64)
    private val texMatrix = FloatArray(16)

    // Latency tracking
    private val interFrameBuf = DoubleArray(120)
    private val gpuSampleBuf = DoubleArray(120)
    private val gpuReadbackBuf = DoubleArray(120)
    private val nativeDecodeBuf = DoubleArray(120)
    private val fullPipelineBuf = DoubleArray(120)
    private var latencyCount = 0

    // Geometry tracking
    private var lockedQuad: Array<Point>? = null
    private var lockedSampleOffset = ColorGrid8HeaderSearch.Candidate(0, 0f, 0f)
    private var headerProbes = 0
    private var headerSearchMs = 0.0
    private var consecutiveFailures = 0
    private val maxRedetectInterval = 60
    private var framesSinceFullDetect = 0

    private var camWidth = 1280
    private var camHeight = 960
    private var isInitialized = false
    private val resultPending = AtomicBoolean(false)
    @Volatile private var released = false
    private var lastCameraTimestampNs = 0L

    private val THUMB_W = 960
    private val THUMB_H = 720
    private var savedThumb = false
    private var lastDiagnosticNs = 0L

    init {
        OpenCvRuntime.ensureLoaded()
        glThread.post {
            glSampler.init(camWidth, camHeight)
            isInitialized = true
        }
        glThread.setOnFrameAvailableListener(this)
    }

    fun setResolution(width: Int, height: Int) {
        camWidth = width
        camHeight = height
        glThread.post {
            glSampler.setCameraDimensions(width, height)
        }
        android.util.Log.d("ColorGrid8GlPipeline", "Resolution set to ${width}x${height}")
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // The callback already runs on the GL handler. Latch first: timestamp
        // describes the previous texture until updateTexImage has completed.
        if (!isInitialized || released) return
        glThread.updateTexImage()
        val timestamp = glThread.getTimestamp()
        if (timestamp <= lastCameraTimestampNs) return
        lastCameraTimestampNs = timestamp
        rateAccumulator.recordCompletion(timestamp)
        if (resultPending.get()) return
        processFrame(timestamp, rateAccumulator.computeFps(timestamp))
    }

    private fun processFrame(cameraTimestampNs: Long, cameraFps: Double) {
        val pipelineStartNs = System.nanoTime()
        glSampler.beginFrame()
        headerProbes = 0
        headerSearchMs = 0.0
        var failureResult: ColorGrid8ProcessResult? = null

        glThread.getTransformMatrix(texMatrix)
        val textureId = glThread.getTextureId()

        val gpuSubmitNs = System.nanoTime()
        var gpuReadyNs = gpuSubmitNs
        var decodeStartNs = 0L
        var decodeEndNs = 0L

        var homographyArr: FloatArray? = null
        var geometryLocked = true

        val canTrack = lockedQuad != null && consecutiveFailures < 10 && framesSinceFullDetect < maxRedetectInterval
        if (!canTrack) {
            geometryLocked = false
            framesSinceFullDetect = 0

            val thumbBytes = glSampler.renderFinderThumbnail(textureId, texMatrix)
            gpuReadyNs = System.nanoTime()
            maybeUpdatePreview(textureId, texMatrix, pipelineStartNs, thumbBytes)

            val thumbRgba = Mat(THUMB_H, THUMB_W, CvType.CV_8UC4)
            thumbRgba.put(0, 0, thumbBytes)
            val thumbGray = Mat(THUMB_H, THUMB_W, CvType.CV_8UC1)
            Imgproc.cvtColor(thumbRgba, thumbGray, Imgproc.COLOR_RGBA2GRAY)

            val attempt = processor.detectFiducials(thumbGray, THUMB_W, THUMB_H)
            thumbRgba.release()

            val acq = attempt.acquisition
            failureResult = ColorGrid8ProcessResult(
                analysis = null, stage = ColorGrid8Stage.FINDERS,
                failure = attempt.failure ?: "Finder geometry not acquired",
                quad = emptyList(), acquisitionMode = "GPU_FINDER",
                finderCandidates = attempt.candidateCount, geometryLocked = false,
                headerStatus = "NOT_REACHED", expectedProfile = profile.toString(), detectedProfile = null,
            )
            if (acq != null) {
                val ordered = acq.quad
                val scaleX = camWidth.toDouble() / THUMB_W.toDouble()
                val scaleY = camHeight.toDouble() / THUMB_H.toDouble()
                if (pipelineStartNs - lastDiagnosticNs >= 1_000_000_000L) {
                    android.util.Log.d("ColorGrid8GlPipeline", "Fiducials ACQUIRED (${acq.mode})! ordered: [0]=${ordered[0]} [1]=${ordered[1]} [2]=${ordered[2]} [3]=${ordered[3]} scaleX=$scaleX scaleY=$scaleY")
                }

                val fo = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS.toDouble()
                val c = profile.cols.toDouble()
                val r = profile.rows.toDouble()
                val isMacro = profile.cols == 480 && profile.rows == 388
                val cellPx = if (isMacro) minOf(1800 / profile.cols, 960 / profile.rows).coerceAtLeast(1).toDouble()
                             else minOf(1764 / profile.cols, 924 / profile.rows).coerceAtLeast(1).toDouble()
                val d = if (isMacro) (33.0 / cellPx) else if (profile.hasCanonicalGpuGeometry) 8.0 else (39.0 / cellPx)
                val gridCorners = MatOfPoint2f(
                    Point(fo - d, fo - d),
                    Point(fo + c + d, fo - d),
                    Point(fo + c + d, fo + r + d),
                    Point(fo - d, fo + r + d)
                )

                if (profile.hasCanonicalGpuGeometry) {
                    val searchStart = System.nanoTime()
                    val quads = Array(ColorGrid8HeaderSearch.ROTATIONS) { rot ->
                        Array(4) { i -> ordered[(i + rot) and 3].let { Point(it.x * scaleX, it.y * scaleY) } }
                    }
                    val matrices = FloatArray(ColorGrid8HeaderSearch.ROTATIONS * 9)
                    try {
                        for (rot in quads.indices) {
                            val camMat = MatOfPoint2f(*quads[rot])
                            val h = Geometry.getPerspectiveTransform(gridCorners, camMat)
                            try {
                                for (row in 0..2) for (col in 0..2) {
                                    matrices[rot * 9 + col * 3 + row] = h.get(row, col)[0].toFloat()
                                }
                            } finally {
                                h.release()
                                camMat.release()
                            }
                        }
                    } finally {
                        gridCorners.release()
                        thumbGray.release()
                    }
                    val headerLuma = glSampler.renderHeaderCandidates(textureId, texMatrix, matrices,
                        ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS, sampleMode)
                    val search = ColorGrid8HeaderSearch.select(profile, headerLuma, headerAnalyzer)
                    val captureDir = ColorGrid8DiagnosticCapture.take()
                    if (captureDir != null) {
                        try {
                            check(captureDir.isDirectory || captureDir.mkdirs())
                            // Fixed filenames bound storage to one capture. Write
                            // metadata last so an interrupted capture is not replayed.
                            java.io.File(captureDir, "frame.json").delete()
                            glSampler.captureCameraRgba(textureId, texMatrix, java.io.File(captureDir, "camera.rgba"))
                            java.io.File(captureDir, "headers.y").writeBytes(headerLuma)
                            val metadata = org.json.JSONObject().apply {
                                put("cameraWidth", camWidth); put("cameraHeight", camHeight)
                                put("cameraTimestampNs", cameraTimestampNs)
                                put("cols", profile.cols); put("rows", profile.rows)
                                put("fps", profile.fps); put("seed", profile.seed); put("version", profile.version)
                                put("sampleMode", sampleMode)
                                put("homographiesColumnMajor", org.json.JSONArray(matrices.toList()))
                                put("surfaceTextureMatrix", org.json.JSONArray(texMatrix.toList()))
                                put("finderCorners", org.json.JSONArray(quads[0].map { listOf(it.x, it.y) }))
                                put("headerAtlasWidth", ColorGrid8HeaderSearch.WIDTH)
                                put("headerAtlasHeight", ColorGrid8HeaderSearch.HEIGHT)
                            }
                            java.io.File(captureDir, "frame.json").writeText(metadata.toString(2))
                            android.util.Log.i("ColorGrid8GlPipeline", "DIAGNOSTIC_CAPTURE complete: $captureDir (excluded from timing)")
                        } catch (e: Exception) {
                            android.util.Log.e("ColorGrid8GlPipeline", "DIAGNOSTIC_CAPTURE failed", e)
                        }
                        return
                    }
                    headerProbes = ColorGrid8HeaderSearch.CANDIDATES
                    headerSearchMs = (System.nanoTime() - searchStart) / 1_000_000.0
                    val selection = search.selection
                    if (selection == null) {
                        lockedQuad = null
                        consecutiveFailures++
                        val probe = search.bestProbe
                        val failure = failureResult.copy(
                            stage = ColorGrid8Stage.HEADER, headerStatus = "INVALID",
                            acquisitionMode = "GPU_HEADER_ATLAS",
                            quad = quads[0].map { doubleArrayOf(it.x, it.y) },
                            headerMs = headerSearchMs,
                            failure = "No matching header in $headerProbes probes; magic=%03X contrast=%.1f".format(
                                probe.rawMagic, probe.contrast),
                        )
                        dispatchResult(failure, cameraTimestampNs, pipelineStartNs, gpuSubmitNs, System.nanoTime(),
                            0L, 0L, cameraFps)
                        return
                    }
                    val candidate = selection.candidate
                    val h = matrices.copyOfRange(candidate.rotation * 9, candidate.rotation * 9 + 9)
                    val quad = quads[candidate.rotation]
                    // The full frame must use precisely the offset validated by
                    // the header atlas, and still pass the native decoder/CRCs.
                    val (y, u, v) = glSampler.renderCellMeans(textureId, texMatrix, h, profile.cols, profile.rows,
                        ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS, sampleMode,
                        offsetX = candidate.dx, offsetY = candidate.dy)
                    gpuReadyNs = System.nanoTime()
                    decodeStartNs = gpuReadyNs
                    val result = processor.processFromGpuCellMeans(profile, y, u, v, quad)
                    decodeEndNs = System.nanoTime()
                    if (result.headerStatus == "VALID") {
                        lockedQuad = quad
                        lockedSampleOffset = candidate
                        consecutiveFailures = 0
                    } else {
                        lockedQuad = null
                        consecutiveFailures++
                    }
                    dispatchResult(result, cameraTimestampNs, pipelineStartNs, gpuSubmitNs, gpuReadyNs,
                        decodeStartNs, decodeEndNs, cameraFps)
                    return
                }

                var foundValid = false
                for (rot in 0..3) {
                    val rotated = Array(4) { ordered[(it + rot) and 3] }
                    val camCorners = Array(4) {
                        Point(rotated[it].x * scaleX, rotated[it].y * scaleY)
                    }
                    val camMat = MatOfPoint2f(*camCorners)
                    val homographyMat = Geometry.getPerspectiveTransform(gridCorners, camMat)
                    val hArr = FloatArray(9)
                    for (row in 0..2) {
                        for (col in 0..2) {
                            val entry = homographyMat.get(row, col)
                            hArr[col * 3 + row] = if (entry != null && entry.isNotEmpty()) entry[0].toFloat() else 0f
                        }
                    }
                    camMat.release()
                    homographyMat.release()

                    val (yMeans, uMeans, vMeans) = if (profile.cols == 480 && profile.rows == 388) {
                        glSampler.renderMacrochromaCellMeans(
                            textureId, texMatrix, hArr, profile.cols, profile.rows,
                            ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS, sampleMode
                        )
                    } else {
                        glSampler.renderCellMeans(
                            textureId, texMatrix, hArr, profile.cols, profile.rows,
                            ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS, sampleMode
                        )
                    }


                    decodeStartNs = System.nanoTime()
                    val processResult = processor.processFromGpuCellMeans(
                        profile = profile,
                        cellMeansY = yMeans,
                        cellMeansU = uMeans,
                        cellMeansV = vMeans,
                        quad = camCorners,
                    )
                    decodeEndNs = System.nanoTime()
                    failureResult = processResult
                    android.util.Log.d("ColorGrid8GlPipeline", "rot=$rot status=${processResult.headerStatus} stage=${processResult.stage} frameIndex=${processResult.analysis?.header?.frameIndex}")

                    if (processResult.headerStatus == "VALID") {
                        lockedQuad = camCorners
                        lockedSampleOffset = ColorGrid8HeaderSearch.Candidate(0, 0f, 0f)
                        geometryLocked = true
                        consecutiveFailures = 0
                        foundValid = true
                        gridCorners.release()
                        thumbGray.release()
                        android.util.Log.d("ColorGrid8GlPipeline", "LOCKED at rot=$rot! Header VALID, frameIndex=${processResult.analysis?.header?.frameIndex}")
                        dispatchResult(
                            processResult, cameraTimestampNs, pipelineStartNs, gpuSubmitNs, gpuReadyNs,
                            decodeStartNs, decodeEndNs, cameraFps
                        )
                        return
                    }
                }
                gridCorners.release()
                if (!foundValid) {
                    consecutiveFailures++
                    lockedQuad = null
                }
            } else {
                consecutiveFailures++
                lockedQuad = null
            }
            thumbGray.release()
        } else {
            framesSinceFullDetect++
        }

        if (geometryLocked && lockedQuad != null) {
            val fo = ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS.toDouble()
            val c = profile.cols.toDouble()
            val r = profile.rows.toDouble()
            val isMacro = profile.cols == 480 && profile.rows == 388
            val cellPx = if (isMacro) minOf(1800 / profile.cols, 960 / profile.rows).coerceAtLeast(1).toDouble()
                         else minOf(1764 / profile.cols, 924 / profile.rows).coerceAtLeast(1).toDouble()
            val d = if (isMacro) (33.0 / cellPx) else if (profile.hasCanonicalGpuGeometry) 8.0 else (39.0 / cellPx)
            val gridCorners = MatOfPoint2f(
                Point(fo - d, fo - d),
                Point(fo + c + d, fo - d),
                Point(fo + c + d, fo + r + d),
                Point(fo - d, fo + r + d)
            )
            val camMat = MatOfPoint2f(*lockedQuad!!)
            val homographyMat = Geometry.getPerspectiveTransform(gridCorners, camMat)

            homographyArr = FloatArray(9)
            for (row in 0..2) {
                for (col in 0..2) {
                    val entry = homographyMat.get(row, col)
                    homographyArr[col * 3 + row] = if (entry != null && entry.isNotEmpty()) entry[0].toFloat() else 0f
                }
            }
            gridCorners.release()
            camMat.release()
            homographyMat.release()

            val (yMeans, uMeans, vMeans) = if (profile.cols == 480 && profile.rows == 388) {
                glSampler.renderMacrochromaCellMeans(
                    textureId, texMatrix, homographyArr, profile.cols, profile.rows,
                    ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS, sampleMode
                )
            } else {
                glSampler.renderCellMeans(
                    textureId, texMatrix, homographyArr, profile.cols, profile.rows,
                    ColorGrid8Spec.FIDUCIAL_OFFSET_CELLS, sampleMode,
                    offsetX = lockedSampleOffset.dx, offsetY = lockedSampleOffset.dy,
                )
            }
            gpuReadyNs = System.nanoTime()

            decodeStartNs = System.nanoTime()
            val processResult = processor.processFromGpuCellMeans(
                profile = profile,
                cellMeansY = yMeans,
                cellMeansU = uMeans,
                cellMeansV = vMeans,
                quad = lockedQuad,
            )
            decodeEndNs = System.nanoTime()
            failureResult = processResult

            if (processResult.headerStatus == "VALID") {
                consecutiveFailures = 0
                maybeUpdatePreview(textureId, texMatrix, pipelineStartNs, null)
                dispatchResult(
                    processResult, cameraTimestampNs, pipelineStartNs, gpuSubmitNs, gpuReadyNs,
                    decodeStartNs, decodeEndNs, cameraFps
                )
                return
            } else {
                consecutiveFailures++
                maybeUpdatePreview(textureId, texMatrix, pipelineStartNs, null)
            }
        }

        // Failure path
        dispatchResult(
            failureResult, cameraTimestampNs, pipelineStartNs, gpuSubmitNs, gpuReadyNs,
            decodeStartNs, decodeEndNs, cameraFps
        )
    }

    private fun maybeUpdatePreview(textureId: Int, texMatrix: FloatArray, pipelineStartNs: Long, existingThumbBytes: ByteArray? = null) {
        if (pipelineStartNs - lastPreviewTimeNs >= previewIntervalNs) {
            lastPreviewTimeNs = pipelineStartNs
            val bytes = existingThumbBytes ?: glSampler.renderFinderThumbnail(textureId, texMatrix)
            System.arraycopy(bytes, 0, previewBuffer, 0, bytes.size)
            onPreviewFrame?.invoke(previewBuffer, THUMB_W, THUMB_H)
        }
    }

    private fun dispatchResult(
        processResult: ColorGrid8ProcessResult?,
        cameraTimestampNs: Long,
        pipelineStartNs: Long,
        gpuSubmitNs: Long,
        gpuReadyNs: Long,
        decodeStartNs: Long,
        decodeEndNs: Long,
        cameraFps: Double
    ) {
        val now = System.nanoTime()
        val gpuSampleMs = max(0.0, (gpuReadyNs - gpuSubmitNs) / 1_000_000.0)
        val gpuReadbackMs = glSampler.readbackMs
        val nativeDecodeMs = if (decodeEndNs > decodeStartNs) (decodeEndNs - decodeStartNs) / 1_000_000.0 else 0.0
        val fullPipelineMs = (now - pipelineStartNs) / 1_000_000.0
        if (now - lastDiagnosticNs >= 1_000_000_000L) {
            lastDiagnosticNs = now
            android.util.Log.d("ColorGrid8GlPipeline",
                "stage=${processResult?.stage} header=${processResult?.headerStatus} " +
                "transport=${processResult?.hasVerifiedTransport} " +
                "frame=${processResult?.analysis?.header?.frameIndex} " +
                "erasures=${processResult?.analysis?.erasures} " +
                "pilotsMs=${processResult?.pilotMs} readbackMs=$gpuReadbackMs " +
                "readbackBytes=${glSampler.readbackBytes} headerProbes=$headerProbes headerSearchMs=$headerSearchMs " +
                "sampleOffset=${lockedSampleOffset.dx},${lockedSampleOffset.dy} " +
                "pipelineMs=$fullPipelineMs failure=${processResult?.failure}")
        }

        val idx = latencyCount % 120
        gpuSampleBuf[idx] = gpuSampleMs
        gpuReadbackBuf[idx] = gpuReadbackMs
        nativeDecodeBuf[idx] = nativeDecodeMs
        fullPipelineBuf[idx] = fullPipelineMs
        latencyCount++

        val result = GlPipelineResult(
            processResult = processResult,
            cameraTimestampNs = cameraTimestampNs,
            gpuSubmitNs = gpuSubmitNs,
            gpuReadyNs = gpuReadyNs,
            decodeStartNs = decodeStartNs,
            decodeEndNs = decodeEndNs,
            gpuSampleMs = gpuSampleMs,
            gpuReadbackMs = gpuReadbackMs,
            nativeDecodeMs = nativeDecodeMs,
            fullPipelineMs = fullPipelineMs,
            cameraFps = cameraFps,
            cameraPath = "CAMERA2_GL",
            gpuPath = "ON",
            nativeDecoder = if (ColorGrid8NativeDecoder.isNativeLoaded) "ON (ARM64 NEON)" else "OFF",
            sampleMode = when (sampleMode) {
                1 -> "2x2"
                2 -> "3x3"
                else -> "1x"
            },
            resolution = "${camWidth}x${camHeight}"
        )

        if (released || !resultPending.compareAndSet(false, true)) return
        try {
            analysisExecutor.execute {
                try {
                    if (!released) onResult?.invoke(result)
                } finally {
                    resultPending.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            resultPending.set(false)
        }
    }

    fun getP50(bufferName: String): Double = getPercentile(bufferName, 50)
    fun getP95(bufferName: String): Double = getPercentile(bufferName, 95)

    private fun getPercentile(bufferName: String, rank: Int): Double {
        if (latencyCount == 0) return 0.0
        val buffer = when (bufferName) {
            "gpuSample" -> gpuSampleBuf
            "gpuReadback" -> gpuReadbackBuf
            "nativeDecode" -> nativeDecodeBuf
            "fullPipeline" -> fullPipelineBuf
            else -> return 0.0
        }
        val n = minOf(latencyCount, 120)
        val copy = buffer.copyOf(n)
        copy.sort()
        val index = ((n - 1) * rank) / 100
        return copy[index.coerceIn(0, n - 1)]
    }

    fun release() {
        released = true
        onResult = null
        onPreviewFrame = null
        glThread.setOnFrameAvailableListener(null)
        glThread.post {
            glSampler.release()
            processor.close()
            isInitialized = false
        }
    }
}
